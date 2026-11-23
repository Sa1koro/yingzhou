import 'dart:async';

import 'package:flutter/gestures.dart';
import 'package:flutter/services.dart';
import 'package:material_ui/material_ui.dart';
import 'package:media_kit/media_kit.dart';

import '../app_state.dart';
import '../desktop/desktop_window.dart';
import '../diagnostics/dev_log.dart';
import '../engine/danmaku_models.dart';
import '../engine/engine_client.dart';
import '../engine/episode_names.dart';
import '../engine/library_models.dart';
import '../engine/media_extras_api.dart';
import '../engine/models.dart' show EngineException;
import '../engine/source_diagnostic.dart';
import '../engine/source_finder.dart';
import '../engine/vod_models.dart';
import '../player_backend.dart';
import '../player_cache.dart';
import '../remote/remote_host.dart';
import '../settings.dart';
import '../ui/device_form.dart';
import '../ui/episode_buttons.dart';
import '../ui/theme.dart';
import '../ui/tv/tv_app.dart' show tvTheme;
import '../ui/tv/tv_focus.dart';
import '../ui/user_mode.dart';
import 'browse_page.dart' show errorText;
import 'danmaku_layer.dart';
import 'danmaku_panel.dart';
import 'drive_sign_in_dialog.dart';
import 'player_dev.dart';
import 'player_mobile.dart';
import 'player_tracks.dart';
import 'seek_bar.dart';
import 'touch_gestures.dart';

part 'player_remote.dart';
part 'player_tv.dart';

/// Full-window player: resolves the episode through the engine, plays it with media_kit (libmpv on desktop),
/// and draws its own controls so they work the same with mouse, touch and keyboard.
///
/// Desktop conventions: click pauses, double-click / F toggles fullscreen, Esc leaves fullscreen, the wheel and
/// ↑/↓ change the volume (M mutes), ←/→ seek 10 s (with Shift 60 s), [ / ] change speed, N / P next / previous
/// episode. The volume is remembered between sessions.
///
/// A line that cannot play the episode (its address does not resolve, the player cannot open it, no picture for 15
/// seconds, or the stream breaks off long before the end) is replaced by the next line that has the episode, carrying
/// on from the position reached. Every line gets one turn; a line the user picked by hand is not switched away from.
///
/// Modes (ui/user_mode.dart). Simple: the choice of line is left out (a line that fails is replaced, with a plain
/// sentence, and the lines only show once none is left to try); speed, danmaku, subtitles and episodes stay. Developer: I shows
/// playback statistics over the picture, 链路 where the video comes from, and every step is written to the log
/// (player_dev.dart, diagnostics/dev_log.dart).
///
/// Seeking: the bar seeks once, when it is let go ([SeekBar]), and shows what is buffered — what the player holds
/// and, for streams the engine caches, what the engine has on disk. The player's own cache is set up for where the
/// video comes from ([PlayerCache]).
class PlayerPage extends StatefulWidget {
  const PlayerPage({super.key, required this.detail, required this.line, required this.episode, this.startPosition, this.sources});

  final VodDetail detail;
  final int line;
  final int episode;

  /// Resume point for the first episode played (from history).
  final Duration? startPosition;

  /// What may be done when no line of the title plays: move on to another site's copy (the detail page's search).
  /// Null for offline and drive playback.
  final PlayerSources? sources;

  @override
  State<PlayerPage> createState() => _PlayerPageState();
}

/// The way out when every line of a title fails: the copies of the same title on other sites, found by the detail
/// page's [SourceFinder] (TVBox / FongMi switch site the same way).
class PlayerSources {
  const PlayerSources({required this.finder, required this.auto, required this.onSwitched});

  final SourceFinder finder;

  /// Whether the player may move to another site by itself: not when the user chose this source, nor when its site
  /// is not `changeable`.
  final bool auto;

  /// The player moved on to [card]'s [detail]; the detail page follows.
  final void Function(VodCard card, VodDetail detail) onSwitched;
}

class _PlayerPageState extends State<PlayerPage> {
  /// The title being played: [PlayerPage.detail], or another site's copy once every line of that one failed.
  late VodDetail _detail = widget.detail;

  /// The sources tried for the episode under way (keys from [sourceKey]); emptied when another episode is chosen.
  final _triedSources = <String>{};
  late final PlayerBackend _backend = AppScope.read(context).playerBackend();
  late final EngineClient _client;
  final _focus = FocusNode();

  /// The remote control on a TV (player_tv.dart).
  final _tvUi = _TvPlayerUi();
  void _tvSet(VoidCallback change) => setState(change);

  /// The LAN remote control: a phone that drives this player (player_remote.dart).
  late final _remote = _PlayerRemote(this);

  /// Whatever changes what is on the screen is also news for a remote.
  @override
  void setState(VoidCallback fn) {
    super.setState(fn);
    RemoteHost.instance.changed();
  }
  final _subs = <StreamSubscription<dynamic>>[];

  late int _line = widget.line;
  late int _episode = widget.episode;

  /// Where the episode being opened carries on from: the history's position on the way in, later the position
  /// reached on the line before. [_pendingSeek] is the same until the player has been sent there.
  Duration? _resumeAt;
  Duration? _pendingSeek;
  String? _status;
  String? _error;
  String _parsedBy = '';

  /// The answer of the last resolve, and whether the player was moved onto its [PlayInfo.relay] because fetching the
  /// video from the source did not work (see [AppState.fetchDirect]).
  PlayInfo? _played;
  bool _onRelay = false;
  bool _controlsVisible = true;
  bool _panelOpen = false;
  bool _opening = false;
  Timer? _hideTimer;
  Timer? _saveTimer;
  int _requestSeq = 0;
  late final SettingsStore _settings;
  Timer? _volumeSave;
  double _volumeBeforeMute = 100;
  String? _osd;
  Timer? _osdTimer;

  // Changing line without being asked. A round starts where the user (or the end of an episode) chose to play,
  // [_originLine] / [_originEpisode]; from there every other line gets one turn before the page gives up.
  late int _originLine = widget.line;
  late int _originEpisode = widget.episode;
  final _failedLines = <int>{};

  /// False once the user picked the line by hand: the page does not switch away from that choice.
  bool _autoLine = true;

  /// How often the position has moved forward since the media was opened; above zero there is a picture.
  int _advances = 0;
  Duration _lastPosition = Duration.zero;
  Timer? _watchdog;
  Timer? _errorCheck;

  /// Where the player was last sent, until it reports a position from there: further steps start from this place,
  /// not from where the picture still is.
  Duration? _seekingTo;
  Timer? _seekingTimeout;

  /// What is buffered, for the seek bar: looked at once a second. [_cacheKey] names the film in the engine's stream
  /// cache when the engine serves it from there.
  final _buffered = ValueNotifier<List<BufferedRange>>(const []);
  Timer? _bufferWatch;
  bool _readingBuffer = false;
  int _bufferTicks = 0;
  String _cacheKey = '';
  List<BufferedRange> _engineRanges = const [];

  /// Subtitles and audio tracks ([PlayerTracks]); the 字幕 panel. What the title's history says to choose, until
  /// the user chooses something else.
  late final PlayerTracks _tracks;
  bool _tracksOpen = false;
  String _savedSubtitle = '';
  String _savedAudio = '';
  Future<void>? _historyRead;

  /// Danmaku: how they look (remembered on this device), this episode's, and the 弹幕 panel.
  late final DanmakuPrefs _danmakuPrefs;
  DanmakuList _danmaku = DanmakuList.empty;
  bool _danmakuLoading = false;
  bool _danmakuAsked = false;
  String? _danmakuError;
  bool _danmakuOpen = false;
  int _danmakuSeq = 0;

  /// Simple, standard or developer (read once: the mode is not changed while a video plays).
  late final UserModeController _userMode;
  bool get _simple => _userMode.simple;
  bool get _dev => _userMode.developer;

  /// Developer mode: the statistics over the picture, the 链路 panel, and what it shows: how long the address took
  /// to find and the first picture to come, and the lines that failed for this episode.
  bool _statsOpen = false;
  bool _chainOpen = false;
  int? _resolveMs;
  int? _firstFrameMs;
  Stopwatch? _sinceOpen;
  final _failedNotes = <({String detail, String shareSummary})>[];
  String? _resolveFailure;

  bool get _anyPanelOpen => _panelOpen || _tracksOpen || _danmakuOpen || _chainOpen;

  /// Phones and tablets: gestures on the picture, full screen and rotation, the lock, picture-in-picture
  /// ([MobilePlayback]); null on desktops and TVs. [_stageKey] keeps the picture and its controls alive when the
  /// layout around them changes (upright with the episodes below, full screen, picture-in-picture).
  MobilePlayback? _mobile;
  bool _formKnown = false;
  final _stageKey = GlobalKey();
  Orientation? _orientation;

  static const _volumeKey = 'player.volume';
  static const _speeds = [0.5, 0.75, 1.0, 1.25, 1.5, 2.0, 3.0];

  /// No picture this long after the player got the address: the line is dead.
  static const _startTimeout = Duration(seconds: 15);

  /// After the player reports an error, how long a line gets to show it is alive: one that has not loaded, and one
  /// that was playing.
  static const _loadGrace = Duration(seconds: 2);
  static const _stallGrace = Duration(seconds: 10);

  /// A stream that ends with more than this left did not finish, it broke off.
  static const _earlyEnd = Duration(seconds: 30);

  Player get _player => _backend.player;
  PlayLine get _currentLine => _detail.lines[_line];
  Episode get _currentEpisode => _currentLine.episodes[_episode];
  bool get _hasNext => _episode + 1 < _currentLine.episodes.length;
  bool get _hasPrevious => _episode > 0;
  bool get _started => _advances > 0;
  bool get _desktop => DesktopWindow.isDesktop;
  DesktopWindow get _window => DesktopWindow.instance;

  @override
  void initState() {
    super.initState();
    final app = AppScope.read(context);
    _client = app.client;
    _settings = app.settings;
    _userMode = app.userMode;
    final savedVolume = double.tryParse(_settings.getString(_volumeKey) ?? '');
    if (savedVolume != null) _player.setVolume(savedVolume.clamp(0, 100));
    _subs.add(
      _player.stream.volume.listen((v) {
        _volumeSave?.cancel();
        _volumeSave = Timer(const Duration(milliseconds: 500), () => _settings.setString(_volumeKey, v.toStringAsFixed(0)));
      }),
    );
    _subs.add(
      _player.stream.completed.listen((done) {
        if (!done || !mounted || _opening || _error != null) return;
        final state = _player.state;
        // A stream that breaks off reports the end like any other; what is left tells the two apart. One that ends
        // before it showed anything never played at all.
        if (!_started) {
          _lineFailed('无法播放', '还没有画面就结束了');
        } else if (state.duration > Duration.zero && state.duration - state.position > _earlyEnd) {
          _lineFailed('播放中断', '播放中途断开（${_TimeLabel.format(state.position)} / ${_TimeLabel.format(state.duration)}）');
        } else if (_hasNext) {
          _playEpisode(_episode + 1);
        }
      }),
    );
    // Resume once the media knows its length (seeking earlier is ignored by some backends).
    _subs.add(
      _player.stream.duration.listen((d) {
        final seek = _pendingSeek;
        if (seek != null && d > Duration.zero && seek < d - const Duration(seconds: 5)) {
          _pendingSeek = null;
          _player.seek(seek);
        }
      }),
    );
    _subs.add(
      _player.stream.position.listen((p) {
        // Stopping the media before the next one resets the position.
        if (_opening) return;
        // The player has taken up the seek: its position is the place to step from again.
        if (_seekingTo != null && (p - _seekingTo!).abs() < const Duration(seconds: 2)) _clearSeekingTo();
        // Moving on from somewhere past zero: frames are coming (a jump to the resume point alone is not that).
        if (_lastPosition > Duration.zero && p > _lastPosition) {
          if (_advances == 0) _firstPicture();
          _advances++;
          _watchdog?.cancel();
          _watchdog = null;
        }
        _lastPosition = p;
      }),
    );
    _saveTimer = Timer.periodic(const Duration(seconds: 10), (_) => _saveProgress());
    _subs.add(
      _player.stream.error.listen((e) {
        // Stopping or replacing the media emits errors (e.g. "empty src" on web), and so does a stream that carries
        // on regardless (a bad frame, a lost segment). The line is given up only if nothing moves afterwards.
        if (!mounted || _opening || _error != null || _errorCheck != null) return;
        final seq = _requestSeq;
        final seen = _advances;
        _errorCheck = Timer(seen > 0 ? _stallGrace : _loadGrace, () {
          _errorCheck = null;
          if (!mounted || seq != _requestSeq || _error != null || _advances != seen) return;
          final state = _player.state;
          // Never loaded; or was playing and is neither paused nor finished, yet stands still.
          if (seen == 0 ? state.duration <= Duration.zero : state.playing && !state.completed) {
            _lineFailed(seen == 0 ? '无法播放' : '播放中断', '播放出错：$e');
          }
        });
      }),
    );
    _bufferWatch = Timer.periodic(const Duration(seconds: 1), (_) => _readBuffered());
    _tracks = PlayerTracks(player: _player, client: _client, settings: _settings, onNotice: (text) => _showOsd(text, const Duration(seconds: 2)));
    _danmakuPrefs = DanmakuPrefs(_settings)..addListener(_danmakuPrefsChanged);
    _historyRead = _client.historyFor(_detail.site, _detail.id).then((h) {
      _savedSubtitle = h?.subtitle ?? '';
      _savedAudio = h?.audio ?? '';
    }).catchError((Object _) {});
    _remoteAttach();
    _open(_line, _episode, resume: widget.startPosition);
    _pokeControls();
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    // Phones and tablets (ui/device_form.dart) get the touch player, decided once when the page first sees the scope.
    if (!_formKnown) {
      _formKnown = true;
      if (isTouch(context)) {
        _mobile = MobilePlayback(
          onChanged: () {
            if (mounted) setState(() {});
          },
          // Leaving the app without picture-in-picture pauses, as phone players do.
          onBackground: () => _player.pause(),
        )
          ..start()
          ..attach(_player);
      }
    }
    final mobile = _mobile;
    if (mobile == null) return;
    // A phone's player goes full screen in landscape; a tablet's shows the picture with the bars either way.
    mobile.rotateToFullScreen = isPhone(context);
    final orientation = MediaQuery.orientationOf(context);
    if (orientation == _orientation) return;
    _orientation = orientation;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) mobile.orientationChanged(orientation);
    });
  }

  @override
  void dispose() {
    _hideTimer?.cancel();
    _saveTimer?.cancel();
    _osdTimer?.cancel();
    _volumeSave?.cancel();
    _watchdog?.cancel();
    _errorCheck?.cancel();
    _seekingTimeout?.cancel();
    _bufferWatch?.cancel();
    _buffered.dispose();
    _settings.setString(_volumeKey, _player.state.volume.toStringAsFixed(0));
    if (_window.isFullScreen) _window.fullScreen = false;
    _window.title = '映舟';
    _saveProgress();
    _remoteDetach();
    _tracks.dispose();
    _danmakuPrefs.dispose();
    _mobile?.dispose();
    for (final s in _subs) {
      s.cancel();
    }
    _player.dispose();
    _focus.dispose();
    _tvUi.dispose();
    super.dispose();
  }

  /// Records where we are, so any client of this engine can resume. Failures are ignored (best effort).
  void _saveProgress({bool episodeStart = false}) {
    final d = _detail;
    final position = episodeStart ? Duration.zero : _player.state.position;
    final duration = episodeStart ? Duration.zero : _player.state.duration;
    if (!episodeStart && duration <= Duration.zero) return;
    _client
        .putHistory(
          HistoryEntry(
            site: d.site,
            vodId: d.id,
            name: d.name,
            pic: d.pic,
            line: _currentLine.name,
            episodeId: _currentEpisode.id,
            episodeName: _currentEpisode.name,
            episodeIndex: _episode,
            positionMs: position.inMilliseconds,
            durationMs: duration.inMilliseconds,
            subtitle: _subtitleChoice(),
            audio: _audioChoice(),
          ),
        )
        .catchError((Object _) {});
  }

  /// The subtitle and audio track to keep for this title: what the user chose here, else what the history had.
  String _subtitleChoice() => _tracks.subtitleChoice.isNotEmpty ? _savedSubtitle = _tracks.subtitleChoice : _savedSubtitle;
  String _audioChoice() => _tracks.audioChoice.isNotEmpty ? _savedAudio = _tracks.audioChoice : _savedAudio;

  /// The user, or the end of the episode before, chose an episode: it starts from the beginning, and a new round of
  /// lines starts with it.
  void _playEpisode(int episode) {
    _originLine = _line;
    _originEpisode = episode;
    _failedLines.clear();
    _failedNotes.clear();
    _triedSources.clear();
    _autoLine = true;
    _open(_line, episode);
  }

  /// The user chose a line: the same episode there, carrying on from where it was. The choice is theirs: if this
  /// line fails, the page says so and stays on it.
  void _pickLine(int line) {
    final lines = _detail.lines;
    if (lines[line].episodes.isEmpty) return;
    final same = lines[line].findEpisode(index: _originEpisode, name: lines[_originLine].episodes[_originEpisode].name);
    final resume = same == null ? null : _reached();
    _originLine = line;
    _originEpisode = same ?? _originEpisode.clamp(0, lines[line].episodes.length - 1);
    _failedLines.clear();
    _failedNotes.clear();
    _autoLine = false;
    _open(line, _originEpisode, resume: resume);
  }

  /// After an error: the same round again, from the line it began on and the position playback got to.
  void _retry() {
    _failedLines.clear();
    _failedNotes.clear();
    _open(_originLine, _originEpisode, resume: _resumeAt);
  }

  /// Where to carry on after a change of line: the position reached, else the one that was still to be sought.
  Duration? _reached() {
    final position = _player.state.position;
    return _started && position > Duration.zero ? position : _resumeAt;
  }

  String _lineLabel(int line) {
    final name = _detail.lines[line].name;
    final label = name.isEmpty ? '线路 ${line + 1}' : name;
    if (_detail.site != widget.detail.site) return '「${AppScope.read(context).siteName(_detail.site)} · $label」';
    return name.isEmpty ? label : '「$name」';
  }

  /// The next line, going round from the current one, that has the episode and has not failed in this round.
  (int, int)? _nextLine() {
    final lines = _detail.lines;
    final wanted = lines[_originLine].episodes[_originEpisode].name;
    for (var step = 1; step < lines.length; step++) {
      final line = (_line + step) % lines.length;
      if (_failedLines.contains(line)) continue;
      final episode = lines[line].findEpisode(index: _originEpisode, name: wanted);
      if (episode != null) return (line, episode);
    }
    return null;
  }

  /// The current line cannot play the episode ([why] in two words for the notice, [reason] in full for the error).
  /// Moves on to the next line, unless the user chose this one by hand or every line has had its turn: then the
  /// error is shown and the page is back on the line the round began on, ready to go again.
  void _lineFailed(String why, String reason, {SourceDiagnostic? issue}) {
    final played = _played;
    if (played != null && played.relay.isNotEmpty && !_onRelay) {
      unawaited(_toRelay(played));
      return;
    }
    _watchdog?.cancel();
    _watchdog = null;
    _errorCheck?.cancel();
    _errorCheck = null;
    final lines = _detail.lines;
    final resume = _reached();
    final failed = _lineLabel(_line);
    _failedLines.add(_line);
    final diagnostic = issue == null ? '' : '\n${issue.report}';
    final shareSummary = '第 ${_line + 1} 条 · $why$diagnostic';
    _failedNotes.add((detail: '${_currentLine.name.isEmpty ? '线路 ${_line + 1}' : _currentLine.name}：$reason$diagnostic', shareSummary: shareSummary));
    final next = _autoLine ? _nextLine() : null;
    _log(DevLevel.warn, '线路', '$failed$why：$reason', shareSummary: shareSummary, detail: {'站点': _detail.site, '选集': _currentEpisode.name, '选集地址': _currentEpisode.id});
    if (next != null) {
      final from = resume != null && resume > Duration.zero ? '，从 ${_TimeLabel.format(resume)} 接着播' : '';
      _log(DevLevel.info, '线路', '换到${_lineLabel(next.$1)}$from', shareSummary: '切换到第 ${next.$1 + 1} 条线路');
      // Simple mode says what happened without naming lines, which it does not show anywhere.
      _showOsd(_simple ? '这条线路打不开，已换到下一条$from' : '$failed$why，已切到${_lineLabel(next.$1)}', const Duration(seconds: 4));
      _open(next.$1, next.$2, resume: resume, automatic: true);
      return;
    }
    final tried = _failedLines.length;
    final String text;
    if (lines.length < 2) {
      text = reason;
    } else if (!_autoLine) {
      text = '$reason\n$failed是手动选的，不会自动换线路';
    } else if (tried < 2) {
      text = '$reason\n其他线路没有这一集';
    } else {
      text = '${tried == lines.length ? '所有 $tried 条线路都试过了' : '有这一集的 $tried 条线路都试过了'}，都没能播放\n最后一条$failed：$reason';
    }
    // Every line had its turn: another site may have the title (only when the lines were changed automatically).
    if (_autoLine && widget.sources?.auto == true && widget.sources!.finder.siteCount > 0 && _detail.name.trim().isNotEmpty) {
      unawaited(_switchSource(text, resume));
      return;
    }
    _giveUp(text, resume);
  }

  /// Nothing more to try: [text] is shown, and the page is back on the line the round began on, ready to go again.
  void _giveUp(String text, Duration? resume) {
    _log(DevLevel.error, '播放器', text.split('\n').first, shareSummary: '尝试 ${_failedLines.length} 条线路后停止播放', detail: {'经过': text});
    _saveProgress();
    // Nothing that was under way counts any more, and nothing keeps loading behind the error.
    ++_requestSeq;
    _player.stop().catchError((Object _) {});
    setState(() {
      _line = _originLine;
      _episode = _originEpisode;
      _resumeAt = resume;
      _status = null;
      _parsedBy = '';
      _error = text;
    });
  }

  /// No line of [_detail] plays the episode: the next copy of the same title on another site that has it takes over,
  /// from the position reached. Any choice the user makes meanwhile (episode, line, retry, the remote) starts a new
  /// request and ends this one. With none left, [text] is shown as before, and that no other site helped.
  Future<void> _switchSource(String text, Duration? resume) async {
    final sources = widget.sources!;
    final finder = sources.finder;
    _saveProgress();
    final seq = ++_requestSeq;
    _player.stop().catchError((Object _) {});
    final wanted = _detail.lines[_originLine].episodes[_originEpisode].name;
    final wantedIndex = _originEpisode;
    final name = _detail.name;
    _triedSources.add(sourceKey(_detail.site, _detail.id));
    finder.markBroken(_detail.site, _detail.id);
    setState(() {
      _error = null;
      _parsedBy = '';
      _status = '${_detail.lines.length > 1 ? '所有线路' : '这条线路'}都没能播放，正在别的站点找《$name》…';
    });
    for (var i = 0; i < 8; i++) {
      final card = await finder.nextExact(skip: _triedSources);
      if (!mounted || seq != _requestSeq) return;
      if (card == null) break;
      _triedSources.add(sourceKey(card.site, card.id));
      final VodDetail d;
      try {
        d = (await _client.detail(card.site, card.id)).withId(card.id);
      } catch (_) {
        finder.markBroken(card.site, card.id);
        continue;
      }
      if (!mounted || seq != _requestSeq) return;
      if (yearsConflict(_detail.year, d.year)) {
        finder.markBroken(card.site, card.id);
        continue;
      }
      // A site without this episode may still have others: skipped for now, not marked.
      final at = matchEpisode(d, name: wanted, index: wantedIndex);
      if (at == null) continue;
      final site = AppScope.read(context).siteName(card.site);
      setState(() {
        _detail = d;
        _line = at.$1;
        _episode = at.$2;
        _originLine = at.$1;
        _originEpisode = at.$2;
        _failedLines.clear();
      });
      final from = resume != null && resume > Duration.zero ? '，从 ${_TimeLabel.format(resume)} 接着播' : '';
      _failedNotes.clear();
      _log(DevLevel.info, '站点', '所有线路都没能播放，已换到「$site」$from', shareSummary: '已换到另一站点', detail: {'站点': card.site, 'vod_id': card.id});
      _showOsd('已换到「$site」$from', const Duration(seconds: 5));
      sources.onSwitched(card, d);
      unawaited(_open(at.$1, at.$2, resume: resume, automatic: true));
      return;
    }
    if (!mounted || seq != _requestSeq) return;
    _giveUp('$text\n别的站点也没有找到能播放的《$name》', resume);
  }

  /// No picture a while after the player got the address: the line is dead.
  void _armWatchdog(int seq) {
    _watchdog?.cancel();
    _watchdog = Timer(_startTimeout, () {
      _watchdog = null;
      if (!mounted || seq != _requestSeq || _started || _error != null || _player.state.completed) return;
      if (_player.state.playing) {
        _lineFailed('没有画面', '等了 ${_startTimeout.inSeconds} 秒还没有画面');
      } else {
        // Paused before anything showed: that is the user's doing. Look again later.
        _armWatchdog(seq);
      }
    });
  }

  /// Resolves the episode on that line and hands the address to the player. [resume] carries a position over;
  /// [automatic] marks a line the page turned to by itself.
  Future<void> _open(int line, int episode, {Duration? resume, bool automatic = false, bool offerDriveSignIn = true}) async {
    final seq = ++_requestSeq;
    final client = _client;
    if (seq > 1) _saveProgress();
    _watchdog?.cancel();
    _watchdog = null;
    _errorCheck?.cancel();
    _errorCheck = null;
    _advances = 0;
    _lastPosition = Duration.zero;
    _resumeAt = _pendingSeek = resume;
    _opening = true;
    _clearSeekingTo();
    _cacheKey = '';
    _engineRanges = const [];
    _buffered.value = const [];
    _played = null;
    _onRelay = false;
    _resolveFailure = null;
    _resolveMs = null;
    _firstFrameMs = null;
    _sinceOpen = null;
    // The danmaku of the episode before go; this one's are asked for once its address is known.
    _danmakuSeq++;
    _danmaku = DanmakuList.empty;
    _danmakuAsked = false;
    _danmakuLoading = false;
    _danmakuError = null;
    setState(() {
      _line = line;
      _episode = episode;
      _status = '正在解析播放地址…';
      _error = null;
      _parsedBy = '';
    });
    // An episode from its beginning goes into the history at once; a line the page turned to, only when it plays.
    if (resume == null && !automatic) _saveProgress(episodeStart: true);
    _window.title = '${_detail.name} · ${_currentEpisode.name} - 映舟';
    // Read before anything is awaited: the page may be gone by then.
    final fetchDirect = AppScope.read(context).fetchDirect;
    await _player.stop();
    final PlayInfo info;
    final asked = Stopwatch()..start();
    try {
      info = await client.resolvePlay(
        _detail.site, _currentLine.name, _currentEpisode.id, direct: fetchDirect, offerDriveSignIn: offerDriveSignIn && !automatic,
      );
    } catch (e) {
      if (mounted && seq == _requestSeq) {
        final issue = e is EngineException ? e.diagnostic : null;
        _resolveFailure = issue?.summary ?? '解析播放地址失败';
        _resolveMs = asked.elapsedMilliseconds;
        _lineFailed('解析失败', '解析失败：${errorText(e)}', issue: issue);
      }
      return;
    }
    if (!mounted || seq != _requestSeq) return;
    final prompt = info.driveSignIn;
    if (prompt != null) {
      final signedIn = await showDialog<bool>(
        context: context,
        builder: (_) => DriveSignInDialog(prompt: prompt, poll: () => client.pollDriveSignIn(_detail.site, prompt.handle)),
      );
      if (!mounted || seq != _requestSeq) return;
      if (signedIn == true) {
        await _open(line, episode, resume: resume, automatic: automatic, offerDriveSignIn: false);
      } else {
        _lineFailed('网盘授权未完成', '网盘授权未完成，请重试当前剧集。');
      }
      return;
    }
    _resolveMs = asked.elapsedMilliseconds;
    _log(DevLevel.info, '解析', '${_lineLabel(line)} ${_currentEpisode.name} → ${info.isDirect ? (info.parsedBy.isEmpty ? '直链' : _parserLabel(info.parsedBy)) : '需要网页嗅探'} · $_resolveMs ms', shareSummary: '第 ${line + 1} 条 · ${info.isDirect ? '取得播放地址' : '需要网页嗅探'} · $_resolveMs ms', detail: {
      '站点': _detail.site,
      '选集地址': _currentEpisode.id,
      if (info.url.isNotEmpty) '播放地址': info.url,
      if (info.headers.isNotEmpty) '请求头': info.headers.entries.map((e) => '${e.key}: ${e.value}').join('\n'),
      if (info.relay.isNotEmpty) '经引擎的地址': info.relay,
      if (info.cache.isNotEmpty) '引擎缓存': info.cache,
    });
    if (!info.isDirect) {
      _lineFailed('需要网页嗅探', '这条线路需要网页嗅探，但引擎没有可用的浏览器内核（桌面需安装 Edge 或 Chrome）');
      return;
    }
    // With a relay, the player fetches the video from the source: the engine's cache is not in play until the relay is.
    final direct = info.relay.isNotEmpty;
    setState(() {
      _played = info;
      _status = '正在缓冲…';
      _parsedBy = info.parsedBy;
      if (info.download.isNotEmpty) _parsedBy = 'offline';
    });
    _cacheKey = direct ? '' : info.cache;
    // The subtitle and audio track of the episode before (or of the last visit) are chosen again in this one.
    final subtitle = _subtitleChoice();
    final audio = _audioChoice();
    await _historyRead;
    if (!mounted || seq != _requestSeq) return;
    _tracks.opened(info.subtitles, subtitle: subtitle.isNotEmpty ? subtitle : _savedSubtitle, audio: audio.isNotEmpty ? audio : _savedAudio);
    unawaited(_loadDanmaku());
    await _startPlayer(seq, info.url, info.headers, engineCached: !direct && (info.cache.isNotEmpty || info.download.isNotEmpty));
  }

  /// Hands [url] to the player; what the player keeps of the video depends on whether the engine keeps it too.
  Future<void> _startPlayer(int seq, String url, Map<String, String> headers, {required bool engineCached}) async {
    try {
      await PlayerCache.prepare(_player, engineCached: engineCached);
      if (!mounted || seq != _requestSeq) return;
      _sinceOpen = Stopwatch()..start();
      await _player.open(Media(url, httpHeaders: headers));
    } catch (e) {
      if (mounted && seq == _requestSeq) _lineFailed('无法播放', '播放出错：$e');
      return;
    }
    if (!mounted || seq != _requestSeq) return;
    _opening = false;
    _armWatchdog(seq);
  }

  /// Fetching the video from the source did not work (a source that only accepts the engine's address, `hosts`
  /// rules only the engine applies…): the same video through the engine, from where it had got to. Once per open.
  Future<void> _toRelay(PlayInfo info) async {
    final seq = ++_requestSeq;
    _watchdog?.cancel();
    _watchdog = null;
    _errorCheck?.cancel();
    _errorCheck = null;
    final resume = _reached();
    _onRelay = true;
    _advances = 0;
    _lastPosition = Duration.zero;
    _resumeAt = _pendingSeek = resume;
    _opening = true;
    _clearSeekingTo();
    _cacheKey = info.cache;
    _engineRanges = const [];
    _buffered.value = const [];
    _log(DevLevel.warn, '播放器', '直连片源没能播放，改为经引擎播放', shareSummary: '改为经引擎播放', detail: {'经引擎的地址': info.relay});
    _showOsd('直连片源没能播放，改为经引擎播放', const Duration(seconds: 4));
    setState(() => _status = '正在缓冲…');
    await _player.stop();
    if (!mounted || seq != _requestSeq) return;
    await _startPlayer(seq, info.relay, info.headers, engineCached: info.cache.isNotEmpty);
  }

  void _log(DevLevel level, String module, String message, {required String shareSummary, Map<String, String> detail = const {}}) =>
      DevLog.instance.add(level, module, message, detail: {'片名': _detail.name, ...detail}, shareSummary: shareSummary);

  /// The first picture of what was opened: how long it took, for the log and 链路.
  void _firstPicture() {
    final ms = _sinceOpen?.elapsedMilliseconds;
    if (ms == null) return;
    _firstFrameMs = ms;
    _log(DevLevel.info, '播放器', '${_lineLabel(_line)} 出画面 · $ms ms', shareSummary: '第 ${_line + 1} 条 · 首帧 $ms ms');
    if (_chainOpen && mounted) setState(() {});
  }

  /// 链路 (developer mode): where what is on the screen comes from.
  PlayChain get _chain {
    final app = AppScope.read(context);
    final played = _played;
    return PlayChain(
      subscription: _detail.site.startsWith('drive:') ? '' : (app.summary?.url ?? ''),
      siteName: app.siteName(_detail.site),
      siteKey: _detail.site,
      runtime: app.siteInfo(_detail.site)?.runtime.name ?? '',
      title: _detail.name,
      vodId: _detail.id,
      lineName: _currentLine.name,
      lineNumber: _line + 1,
      lineCount: _detail.lines.length,
      episodeName: _currentEpisode.name,
      episodeId: _currentEpisode.id,
      played: played,
      onRelay: _onRelay,
      parser: played == null || _parsedBy.isEmpty ? '直链，不用解析' : _parserLabel(_parsedBy),
      resolveMs: _resolveMs,
      resolveFailed: _resolveFailure != null,
      firstFrameMs: _firstFrameMs,
      failed: List.of(_failedNotes),
    );
  }

  void _toggleStats() => setState(() => _statsOpen = !_statsOpen);

  void _openChain() => setState(() {
    _panelOpen = false;
    _tracksOpen = false;
    _danmakuOpen = false;
    _chainOpen = true;
  });

  /// The line, how its address was found, and where the video comes from, for the bars and the info under the picture.
  String get _lineCaption => [
    _currentLine.name,
    if (_parsedBy.isNotEmpty) _parserLabel(_parsedBy),
    if (_played?.relay.isNotEmpty == true) _onRelay ? '经引擎' : '直连片源',
  ].join(' · ');

  /// Asks the engine for this episode's danmaku (the site's, else the services the user switched on). Nothing is
  /// asked while danmaku are off.
  Future<void> _loadDanmaku({bool refresh = false}) async {
    if (!_danmakuPrefs.enabled) return;
    final seq = ++_danmakuSeq;
    final line = _currentLine;
    final episode = _currentEpisode;
    setState(() {
      _danmakuAsked = true;
      _danmakuLoading = true;
      _danmakuError = null;
    });
    try {
      final list = await _client.danmaku(
        site: _detail.site,
        line: line.name,
        episodeId: episode.id,
        title: _detail.name,
        episode: episode.name,
        refresh: refresh,
      );
      if (!mounted || seq != _danmakuSeq) return;
      setState(() {
        _danmaku = list;
        _danmakuLoading = false;
      });
    } catch (e) {
      if (!mounted || seq != _danmakuSeq) return;
      setState(() {
        _danmakuLoading = false;
        _danmakuError = '弹幕加载失败：${errorText(e)}';
      });
    }
  }

  void _danmakuPrefsChanged() {
    if (!mounted) return;
    setState(() {});
    // Turned on for an episode whose danmaku were not asked for yet.
    if (_danmakuPrefs.enabled && !_danmakuAsked && !_opening) unawaited(_loadDanmaku());
  }

  void _toggleDanmaku() {
    final on = !_danmakuPrefs.enabled;
    _danmakuPrefs.update(enabled: on);
    _showOsd(on ? (_danmaku.items.isEmpty && _danmakuAsked && !_danmakuLoading ? '弹幕已开启（这一集没有弹幕）' : '弹幕已开启') : '弹幕已关闭');
  }

  /// A danmaku file from disk (Bilibili XML, JSON, ASS), read by the engine, for the episode playing.
  Future<void> _loadDanmakuFile() async {
    final PickedFile? file;
    try {
      file = await filePicker('弹幕', const ['xml', 'json', 'ass']);
    } catch (e) {
      _showOsd('打不开文件选择窗口：$e', const Duration(seconds: 3));
      return;
    }
    if (file == null) return;
    try {
      final list = await _client.parseDanmaku(file.name, file.bytes);
      if (!mounted) return;
      _danmakuSeq++;
      setState(() {
        _danmaku = list;
        _danmakuAsked = true;
        _danmakuLoading = false;
        _danmakuError = null;
      });
      if (!_danmakuPrefs.enabled) _danmakuPrefs.update(enabled: true);
      _showOsd('已加载 ${list.items.length} 条弹幕', const Duration(seconds: 2));
    } catch (e) {
      _showOsd('弹幕文件读不出来：${errorText(e)}', const Duration(seconds: 3));
    }
  }

  bool _danmakuKey(LogicalKeyboardKey key) {
    final keys = HardwareKeyboard.instance;
    if (key != LogicalKeyboardKey.keyD || keys.isControlPressed || keys.isAltPressed || keys.isMetaPressed) return false;
    _toggleDanmaku();
    return true;
  }

  /// The engine names parsers it made up from a site's `playUrl` "sniff" / "json".
  static String _parserLabel(String parsedBy) => switch (parsedBy) {
    'sniff' => '网页嗅探',
    'json' => '经站点解析',
    'offline' => '离线播放（已下载）',
    _ => '经 $parsedBy 解析',
  };

  void _pokeControls() {
    _hideTimer?.cancel();
    if (!_controlsVisible) setState(() => _controlsVisible = true);
    _hideTimer = Timer(const Duration(seconds: 3), () {
      if (mounted && _player.state.playing && !_anyPanelOpen) setState(() => _controlsVisible = false);
    });
  }

  /// A short notice in the middle of the picture (seek, volume, speed, a change of line).
  void _showOsd(String text, [Duration duration = const Duration(milliseconds: 900)]) {
    _osdTimer?.cancel();
    setState(() => _osd = text);
    _osdTimer = Timer(duration, () {
      if (mounted) setState(() => _osd = null);
    });
  }

  void _clearSeekingTo() {
    _seekingTimeout?.cancel();
    _seekingTimeout = null;
    _seekingTo = null;
  }

  /// Sends the player to [target]. Until it reports a position from there, [_seekingTo] stands for where it is.
  void _seekTo(Duration target) {
    _seekingTimeout?.cancel();
    _seekingTo = target;
    _seekingTimeout = Timer(const Duration(seconds: 3), _clearSeekingTo);
    _player.seek(target);
    _pokeControls();
  }

  void _seekBy(Duration delta) {
    final duration = _player.state.duration;
    // Steps add up: the second press of a key starts from where the first one sent the player.
    var target = (_seekingTo ?? _player.state.position) + delta;
    if (target < Duration.zero) target = Duration.zero;
    if (duration > Duration.zero && target > duration) target = duration;
    _seekTo(target);
    final seconds = delta.inSeconds.abs();
    _showOsd('${delta.isNegative ? '⏪' : '⏩'} $seconds 秒   ${_TimeLabel.format(target)} / ${_TimeLabel.format(duration)}');
  }

  /// Looks at what is buffered: what the player holds, and what the engine's stream cache has of this film.
  Future<void> _readBuffered() async {
    if (_readingBuffer || _opening || !mounted) return;
    _readingBuffer = true;
    final seq = _requestSeq;
    try {
      final own = await PlayerCache.read(_player);
      final key = _cacheKey;
      // The engine is asked every other time: it is a request over the network when the engine is another machine.
      if (key.isNotEmpty && _bufferTicks++ % 2 == 0) {
        try {
          final coverage = await _client.streamCoverage(key);
          if (seq == _requestSeq) _engineRanges = coverage.ranges;
        } on EngineException {
          // An engine without a stream cache, or one that is busy: the bar shows what it knew.
        }
      }
      if (!mounted || seq != _requestSeq) return;
      _buffered.value = PlayerCache.merge([...own.ranges, ..._engineRanges]);
      // The player's cache file only grows while the video is open; when it has grown too big, the video is opened
      // again where it is, which starts a new file.
      if (own.diskBytes > PlayerCache.diskLimit && _started && _error == null) {
        _showOsd('播放器缓存已满，正在重新载入…', const Duration(seconds: 3));
        _open(_line, _episode, resume: _player.state.position, automatic: true);
      }
    } finally {
      _readingBuffer = false;
    }
  }

  void _changeVolume(double delta) {
    final v = (_player.state.volume + delta).clamp(0.0, 100.0);
    _player.setVolume(v);
    _showOsd(v == 0 ? '🔇 静音' : '🔊 音量 ${v.round()}%');
  }

  void _toggleMute() {
    final v = _player.state.volume;
    if (v > 0) {
      _volumeBeforeMute = v;
      _player.setVolume(0);
      _showOsd('🔇 静音');
    } else {
      _player.setVolume(_volumeBeforeMute <= 0 ? 60 : _volumeBeforeMute);
      _showOsd('🔊 音量 ${_volumeBeforeMute.round()}%');
    }
  }

  void _stepSpeed(int step) {
    final i = _speeds.indexOf(_player.state.rate);
    final next = _speeds[((i < 0 ? _speeds.indexOf(1.0) : i) + step).clamp(0, _speeds.length - 1)];
    _player.setRate(next);
    _showOsd('倍速 ${next}x');
  }

  void _toggleFullScreen() {
    if (!_window.supported) return;
    _window.toggleFullScreen();
    setState(() {});
  }

  void _back() {
    final mobile = _mobile;
    if (mobile != null && mobile.locked) {
      // Locked: nothing but the lock button does anything, the back gesture included.
      _showOsd('屏幕已锁定，点左侧的锁解锁', const Duration(seconds: 2));
      _pokeControls();
      return;
    }
    if (mobile != null && mobile.fullScreen && !_anyPanelOpen) {
      mobile.setFullScreen(false);
      return;
    }
    if (_window.isFullScreen && !_window.pinnedFullScreen) {
      _window.fullScreen = false;
      setState(() {});
    } else if (_panelOpen) {
      setState(() => _panelOpen = false);
    } else if (_tracksOpen) {
      setState(() => _tracksOpen = false);
    } else if (_danmakuOpen) {
      setState(() => _danmakuOpen = false);
    } else if (_chainOpen) {
      setState(() => _chainOpen = false);
    } else {
      Navigator.of(context).maybePop();
    }
  }

  KeyEventResult _onKey(FocusNode node, KeyEvent event) {
    if (_tvMode) return _onTvKey(event);
    if (event is! KeyDownEvent && event is! KeyRepeatEvent) return KeyEventResult.ignored;
    final key = event.logicalKey;
    final shift = HardwareKeyboard.instance.isShiftPressed;
    final step = Duration(seconds: shift ? 60 : 10);
    if (key == LogicalKeyboardKey.space || key == LogicalKeyboardKey.enter || key == LogicalKeyboardKey.select) {
      _player.playOrPause();
    } else if (key == LogicalKeyboardKey.arrowRight) {
      _seekBy(step);
    } else if (key == LogicalKeyboardKey.arrowLeft) {
      _seekBy(-step);
    } else if (key == LogicalKeyboardKey.arrowUp) {
      _changeVolume(5);
    } else if (key == LogicalKeyboardKey.arrowDown) {
      _changeVolume(-5);
    } else if (key == LogicalKeyboardKey.keyM) {
      _toggleMute();
    } else if (key == LogicalKeyboardKey.keyF) {
      _toggleFullScreen();
    } else if (key == LogicalKeyboardKey.bracketLeft) {
      _stepSpeed(-1);
    } else if (key == LogicalKeyboardKey.bracketRight) {
      _stepSpeed(1);
    } else if ((key == LogicalKeyboardKey.keyN || key == LogicalKeyboardKey.pageDown) && _hasNext) {
      _playEpisode(_episode + 1);
    } else if ((key == LogicalKeyboardKey.keyP || key == LogicalKeyboardKey.pageUp) && _hasPrevious) {
      _playEpisode(_episode - 1);
    } else if (key == LogicalKeyboardKey.escape || key == LogicalKeyboardKey.goBack) {
      _back();
    } else if (_dev && key == LogicalKeyboardKey.keyI) {
      _toggleStats();
    } else if (_tracks.handleKey(key) || _danmakuKey(key)) {
      // Subtitles and audio: C, J, Z, X, A; danmaku: D.
    } else {
      return KeyEventResult.ignored;
    }
    _pokeControls();
    return KeyEventResult.handled;
  }

  @override
  Widget build(BuildContext context) {
    final pip = _mobile?.inPip ?? false;
    final inline = _inline(context);
    // The side panels over the picture; upright on a phone they open under it instead (see _buildPhoneInfo).
    final sidePanels = !inline && !pip;
    // Everything drawn over the picture (bars, panels, notices) is dark whatever the app's theme is: it sits on video.
    return _touchFrame(context, inline, Theme(data: tvTheme(context, buildDarkTheme()), child: Builder(builder: (context) => Scaffold(
      backgroundColor: Colors.black,
      body: Focus(
        focusNode: _focus,
        autofocus: true,
        onKeyEvent: _onKey,
        child: MouseRegion(
          onHover: (_) => _pokeControls(),
          cursor: _controlsVisible ? SystemMouseCursors.basic : SystemMouseCursors.none,
          child: Listener(
            // The mouse wheel changes the volume, like desktop players.
            onPointerSignal: (e) {
              if (e is PointerScrollEvent) _changeVolume(e.scrollDelta.dy < 0 ? 5 : -5);
            },
            child: Stack(
              fit: StackFit.expand,
              children: [
                // The picture alone listens for clicks and double clicks. A button inside this detector would have to
                // wait until a click can no longer become a double click (0.3 s) before it reacts, so the layers
                // above are its siblings, and whatever in them is only for show lets clicks through to it.
                // Phones and tablets: the gestures of phone video players (TouchGestureLayer).
                if (_mobile != null) _buildTouchLayer() else
                GestureDetector(
                  behavior: HitTestBehavior.opaque,
                  // Desktop: click pauses, double-click goes fullscreen. Touch: tap shows/hides the controls.
                  onTap: _desktop
                      ? () {
                          _player.playOrPause();
                          _pokeControls();
                        }
                      : () => _controlsVisible ? setState(() => _controlsVisible = false) : _pokeControls(),
                  onDoubleTap: _desktop ? _toggleFullScreen : _player.playOrPause,
                  child: _backend.view,
                ),
                // Danmaku over the picture, under everything else; clicks go through to the picture.
                if (_danmakuPrefs.enabled && _danmaku.items.isNotEmpty && !pip)
                  IgnorePointer(child: DanmakuOverlay(key: const Key('danmaku-overlay'), player: _player, comments: _danmaku.items, prefs: _danmakuPrefs)),
                AnimatedOpacity(
                  opacity: _barsVisible ? 1 : 0,
                  duration: const Duration(milliseconds: 200),
                  child: IgnorePointer(ignoring: !_barsVisible, child: _buildControls(context)),
                ),
                // The lock, over the picture when it fills the screen; a locked screen shows nothing else.
                if (_mobile != null && !inline && !pip)
                  AnimatedOpacity(
                    opacity: _controlsVisible ? 1 : 0,
                    duration: const Duration(milliseconds: 200),
                    child: IgnorePointer(
                      ignoring: !_controlsVisible,
                      child: PlayerLockButton(
                        locked: _mobile!.locked,
                        onPressed: () {
                          _mobile!.toggleLock();
                          _showOsd(_mobile!.locked ? '屏幕已锁定' : '屏幕已解锁');
                          _pokeControls();
                        },
                      ),
                    ),
                  ),
                // Developer mode: the statistics, under the top bar; clicks go through to the picture.
                if (_statsOpen && !pip) Positioned(left: 16, top: 64, child: IgnorePointer(child: PlayerStatsOverlay(player: _player, sourceFailure: _resolveFailure))),
                if (!pip) _buildStatus(),
                if (_osd != null && !pip)
                  IgnorePointer(
                    child: Align(
                      alignment: const Alignment(0, -0.55),
                      child: Container(
                        padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 10),
                        decoration: BoxDecoration(color: Colors.black.withValues(alpha: 0.6), borderRadius: BorderRadius.circular(10)),
                        child: Text(
                          _osd!,
                          style: const TextStyle(color: Colors.white, fontSize: 16, fontFeatures: [FontFeature.tabularFigures()]),
                        ),
                      ),
                    ),
                  ),
                if (_panelOpen && sidePanels) _tvMode ? _buildTvEpisodePanel(context) : _buildEpisodePanel(context),
                if (_tvUi.settingsOpen) _buildTvSettings(context),
                if (_tracksOpen && sidePanels) PlayerSidePanel(onClose: () => setState(() => _tracksOpen = false), child: TracksPanel(tracks: _tracks)),
                if (_chainOpen && sidePanels) PlayerSidePanel(width: 420, onClose: () => setState(() => _chainOpen = false), child: PlayerChainPanel(chain: _chain)),
                if (_danmakuOpen && sidePanels)
                  PlayerSidePanel(
                    onClose: () => setState(() => _danmakuOpen = false),
                    child: DanmakuPanel(
                      prefs: _danmakuPrefs,
                      list: _danmaku,
                      loading: _danmakuLoading,
                      error: _danmakuError,
                      onReload: () => _loadDanmaku(refresh: true),
                      onLoadFile: _loadDanmakuFile,
                    ),
                  ),
              ],
            ),
          ),
        ),
      ),
    ))));
  }

  Widget _buildStatus() {
    return StreamBuilder<bool>(
      stream: _player.stream.buffering,
      initialData: false,
      builder: (context, snap) {
        final playingStarted = _player.state.duration > Duration.zero;
        final text = _error ?? (snap.data == true || !playingStarted ? _status : null);
        if (text == null) return const SizedBox.shrink();
        return Center(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              // The spinner and the text are for reading; only the buttons under an error take clicks.
              IgnorePointer(
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    if (_error == null) const CircularProgressIndicator(color: Colors.white70),
                    const SizedBox(height: 16),
                    Padding(
                      padding: const EdgeInsets.symmetric(horizontal: 32),
                      child: Text(
                        text,
                        textAlign: TextAlign.center,
                        style: const TextStyle(color: Colors.white70),
                      ),
                    ),
                  ],
                ),
              ),
              if (_error != null) ...[
                const SizedBox(height: 16),
                Wrap(
                  spacing: 12,
                  runSpacing: 8,
                  alignment: WrapAlignment.center,
                  children: [
                    TvAutofocus(child: FilledButton.tonal(onPressed: _retry, child: const Text('重试'))),
                    // The lines stay the user's to choose from when the page has run out of them.
                    if (_detail.lines.length > 1) OutlinedButton(onPressed: () => setState(() => _panelOpen = true), child: const Text('换线路')),
                  ],
                ),
              ],
            ],
          ),
        );
      },
    );
  }

  Widget _buildControls(BuildContext context) {
    final title = '${_detail.name} · ${_currentEpisode.name}';
    return Stack(
      fit: StackFit.expand,
      children: [
        // Shading behind the two bars. A decorated box takes every click inside it, so it must not get any: clicks
        // between the buttons belong to the picture underneath.
        const IgnorePointer(
          child: DecoratedBox(
            decoration: BoxDecoration(
              gradient: LinearGradient(
                begin: Alignment.topCenter,
                end: Alignment.bottomCenter,
                colors: [Color(0xB0000000), Color(0x00000000), Color(0x00000000), Color(0xC0000000)],
                stops: [0, 0.22, 0.7, 1],
              ),
            ),
          ),
        ),
        if (_mobile != null) _buildTouchBars(context, title) else _buildBars(context, title),
      ],
    );
  }

  Widget _buildBars(BuildContext context, String title) {
    return SafeArea(
      child: Column(
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(8, 8, 16, 0),
            child: Row(
              children: [
                IconButton(
                  tooltip: '返回 (Esc)',
                  onPressed: _back,
                  icon: const Icon(Icons.arrow_back, color: Colors.white),
                ),
                const SizedBox(width: 4),
                Expanded(
                  child: IgnorePointer(
                    child: Text(
                      title,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(color: Colors.white, fontSize: 17, fontWeight: FontWeight.w600),
                    ),
                  ),
                ),
                IgnorePointer(
                  child: Text(
                    _lineCaption,
                    style: const TextStyle(color: Colors.white60, fontSize: 13),
                  ),
                ),
              ],
            ),
          ),
          const Spacer(),
          ValueListenableBuilder<List<BufferedRange>>(
            valueListenable: _buffered,
            builder: (context, buffered, _) => StreamBuilder<Duration>(
              stream: _player.stream.position,
              initialData: _player.state.position,
              builder: (context, snap) => SeekBar(
                key: const Key('seek-bar'),
                position: snap.data ?? Duration.zero,
                duration: _player.state.duration,
                buffered: buffered,
                onSeek: _seekTo,
                onInteraction: _pokeControls,
              ),
            ),
          ),
          Padding(
            padding: const EdgeInsets.fromLTRB(8, 0, 8, 8),
            child: Row(
              children: [
                if (_hasPrevious)
                  IconButton(
                    tooltip: '上一集 (P)',
                    onPressed: () => _playEpisode(_episode - 1),
                    icon: const Icon(Icons.skip_previous_rounded, color: Colors.white),
                  ),
                StreamBuilder<bool>(
                  stream: _player.stream.playing,
                  initialData: _player.state.playing,
                  builder: (context, snap) => IconButton(
                    tooltip: '播放 / 暂停 (空格)',
                    iconSize: 32,
                    focusNode: _tvUi.playButton,
                    onPressed: _player.playOrPause,
                    icon: Icon(snap.data == true ? Icons.pause_rounded : Icons.play_arrow_rounded, color: Colors.white),
                  ),
                ),
                if (_hasNext)
                  IconButton(
                    tooltip: '下一集 (N)',
                    onPressed: () => _playEpisode(_episode + 1),
                    icon: const Icon(Icons.skip_next_rounded, color: Colors.white),
                  ),
                const SizedBox(width: 8),
                IgnorePointer(child: _TimeLabel(player: _player)),
                const Spacer(),
                if (_desktop) _VolumeControl(player: _player, onToggleMute: _toggleMute),
                // The remote has the 设置 panel (menu key) for the speed, which also holds line, subtitles and danmaku.
                if (!_tvMode) _SpeedButton(player: _player, speeds: _speeds),
                if (_tvMode)
                  TextButton.icon(
                    key: const Key('tv-settings-button'),
                    onPressed: _tvToggleSettings,
                    icon: const Icon(Icons.tune_rounded, color: Colors.white),
                    label: const Text('设置', style: TextStyle(color: Colors.white)),
                  ),
                if (_dev) ...[
                  TextButton(key: const Key('stats-button'), onPressed: _toggleStats, child: Text('统计', style: TextStyle(color: _statsOpen ? TvColors.coral : Colors.white))),
                  TextButton(key: const Key('chain-button'), onPressed: _openChain, child: const Text('链路', style: TextStyle(color: Colors.white))),
                ],
                IconButton(
                  key: const Key('danmaku-toggle'),
                  tooltip: _danmakuPrefs.enabled ? '关闭弹幕 (D)' : '打开弹幕 (D)',
                  onPressed: _toggleDanmaku,
                  icon: Icon(_danmakuPrefs.enabled ? Icons.chat_bubble_rounded : Icons.chat_bubble_outline_rounded, color: Colors.white),
                ),
                TextButton(
                  key: const Key('danmaku-button'),
                  onPressed: () => setState(() {
                    _panelOpen = false;
                    _tracksOpen = false;
                    _chainOpen = false;
                    _danmakuOpen = true;
                  }),
                  child: const Text('弹幕', style: TextStyle(color: Colors.white)),
                ),
                TextButton.icon(
                  key: const Key('tracks-button'),
                  onPressed: () => setState(() {
                    _panelOpen = false;
                    _danmakuOpen = false;
                    _chainOpen = false;
                    _tracksOpen = true;
                  }),
                  icon: const Icon(Icons.subtitles_outlined, color: Colors.white),
                  label: const Text('字幕', style: TextStyle(color: Colors.white)),
                ),
                TextButton.icon(
                  onPressed: () => setState(() => _panelOpen = true),
                  icon: const Icon(Icons.playlist_play_rounded, color: Colors.white),
                  label: const Text('选集', style: TextStyle(color: Colors.white)),
                ),
                // Not in the TV layout on a desktop, which is full screen for as long as it lasts.
                if (_window.supported && !_window.pinnedFullScreen)
                  IconButton(
                    tooltip: _window.isFullScreen ? '退出全屏 (Esc)' : '全屏 (F / 双击)',
                    onPressed: _toggleFullScreen,
                    icon: Icon(_window.isFullScreen ? Icons.fullscreen_exit_rounded : Icons.fullscreen_rounded, color: Colors.white),
                  ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildEpisodePanel(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Positioned.fill(
      child: GestureDetector(
        onTap: () => setState(() => _panelOpen = false),
        child: ColoredBox(
          color: Colors.black38,
          child: Align(
            alignment: Alignment.centerRight,
            child: GestureDetector(
              onTap: () {},
              child: Container(
                width: 360,
                color: scheme.surfaceContainer.withValues(alpha: 0.96),
                child: SafeArea(
                  child: ListView(
                    padding: const EdgeInsets.all(16),
                    children: [
                      if (_linesOffered) ...[
                        Text('线路', style: TextStyle(color: scheme.onSurfaceVariant)),
                        const SizedBox(height: 8),
                        Wrap(
                          spacing: 8,
                          runSpacing: 8,
                          children: [
                            for (var i = 0; i < _detail.lines.length; i++)
                              ChoiceChip(
                                label: Text(_detail.lines[i].name),
                                selected: i == _line,
                                onSelected: (_) {
                                  setState(() => _panelOpen = false);
                                  _pickLine(i);
                                },
                              ),
                          ],
                        ),
                        const SizedBox(height: 18),
                      ],
                      Text('选集', style: TextStyle(color: scheme.onSurfaceVariant)),
                      const SizedBox(height: 8),
                      EpisodeButtons(
                        naming: EpisodeNaming.of(_detail.lines, _line, title: _detail.name),
                        buttonWidth: 98,
                        maxColumns: 1,
                        button: (context, i, label, row) {
                          final style = row ? ButtonStyle(padding: const WidgetStatePropertyAll(EdgeInsets.symmetric(horizontal: 12)), alignment: Alignment.centerLeft, shape: WidgetStatePropertyAll(RoundedRectangleBorder(borderRadius: BorderRadius.circular(10)))) : null;
                          return i == _episode
                              ? FilledButton(onPressed: null, style: style, child: label)
                              : OutlinedButton(
                                  onPressed: () {
                                    setState(() => _panelOpen = false);
                                    _playEpisode(i);
                                  },
                                  style: style,
                                  child: label,
                                );
                        },
                      ),
                    ],
                  ),
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }

  /// The lines are there to choose from: always, except in simple mode, where they only show once the page has run
  /// out of lines to try by itself (the way out of an error).
  bool get _linesOffered => !_simple || _error != null;

  // ------------------------------------------------------------------ phones and tablets

  /// The bars over the picture: not while the screen is locked or the app is a picture-in-picture window.
  bool get _barsVisible => _controlsVisible && !(_mobile?.locked ?? false) && !(_mobile?.inPip ?? false);

  /// A phone or tablet held upright, not in full screen: the picture on top, the episodes under it.
  bool _inline(BuildContext context) {
    final mobile = _mobile;
    return mobile != null && !mobile.fullScreen && !mobile.inPip && MediaQuery.orientationOf(context) == Orientation.portrait;
  }

  /// Around the page on phones and tablets: the back gesture leaves the lock, a panel or full screen before the page;
  /// upright, the picture is a 16:9 box at the top with the title, lines and episodes under it.
  Widget _touchFrame(BuildContext context, bool inline, Widget page) {
    final mobile = _mobile;
    if (mobile == null) return page;
    final stage = KeyedSubtree(key: _stageKey, child: AnnotatedRegion<SystemUiOverlayStyle>(value: SystemUiOverlayStyle.light, child: page));
    return PopScope(
      canPop: !(mobile.locked || mobile.fullScreen || _anyPanelOpen),
      onPopInvokedWithResult: (didPop, _) {
        if (!didPop) _back();
      },
      child: !inline
          ? stage
          : ColoredBox(
              color: Colors.black,
              child: Column(
                children: [
                  SizedBox(height: MediaQuery.paddingOf(context).top),
                  AspectRatio(
                    aspectRatio: 16 / 9,
                    child: MediaQuery.removePadding(context: context, removeTop: true, removeBottom: true, child: stage),
                  ),
                  Expanded(child: _buildPhoneInfo(context)),
                ],
              ),
            ),
    );
  }

  Widget _buildTouchLayer() {
    final mobile = _mobile!;
    return TouchGestureLayer(
      player: _player,
      locked: mobile.locked,
      onTap: () => _controlsVisible ? setState(() => _controlsVisible = false) : _pokeControls(),
      // Left third back 10 s, right third on 10 s, the middle plays / pauses.
      onDoubleTap: (side) {
        if (side == 0) {
          _player.playOrPause();
          _pokeControls();
        } else {
          _seekBy(Duration(seconds: 10 * side));
        }
      },
      onSeek: (target) {
        _seekTo(target);
        _showOsd('${SeekBar.format(target)} / ${SeekBar.format(_player.state.duration)}');
      },
      onBrightness: mobile.brightnessTouched,
      child: _backend.view,
    );
  }

  Widget _buildTouchBars(BuildContext context, String title) {
    final mobile = _mobile!;
    final compact = _inline(context);
    final seekBar = ValueListenableBuilder<List<BufferedRange>>(
      valueListenable: _buffered,
      builder: (context, buffered, _) => StreamBuilder<Duration>(
        stream: _player.stream.position,
        initialData: _player.state.position,
        builder: (context, snap) => SeekBar(
          key: const Key('seek-bar'),
          position: snap.data ?? Duration.zero,
          duration: _player.state.duration,
          buffered: buffered,
          onSeek: _seekTo,
          onInteraction: _pokeControls,
          height: 48,
        ),
      ),
    );
    return TouchPlayerBars(
      title: compact ? _currentEpisode.name : title,
      subtitle: _lineCaption,
      player: _player,
      seekBar: seekBar,
      compact: compact,
      fullScreen: mobile.fullScreen,
      onBack: _back,
      onFullScreen: () {
        mobile.setFullScreen(!mobile.fullScreen);
        _pokeControls();
      },
      onPrevious: _hasPrevious ? () => _playEpisode(_episode - 1) : null,
      onNext: _hasNext ? () => _playEpisode(_episode + 1) : null,
      onPip: mobile.pipSupported && !compact ? _enterPip : null,
      actions: [
        if (_dev) ...[
          TextButton(key: const Key('stats-button'), onPressed: _toggleStats, child: Text('统计', style: TextStyle(color: _statsOpen ? TvColors.coral : Colors.white))),
          TextButton(key: const Key('chain-button'), onPressed: () => _openPanel(chain: true), child: const Text('链路', style: TextStyle(color: Colors.white))),
        ],
        _SpeedButton(player: _player, speeds: _speeds),
        IconButton(
          key: const Key('danmaku-toggle'),
          tooltip: _danmakuPrefs.enabled ? '关闭弹幕' : '打开弹幕',
          onPressed: _toggleDanmaku,
          icon: Icon(_danmakuPrefs.enabled ? Icons.chat_bubble_rounded : Icons.chat_bubble_outline_rounded, color: Colors.white),
        ),
        TextButton(key: const Key('danmaku-button'), onPressed: () => _openPanel(danmaku: true), child: const Text('弹幕', style: TextStyle(color: Colors.white))),
        TextButton(key: const Key('tracks-button'), onPressed: () => _openPanel(tracks: true), child: const Text('字幕', style: TextStyle(color: Colors.white))),
        TextButton(onPressed: () => _openPanel(episodes: true), child: const Text('选集', style: TextStyle(color: Colors.white))),
      ],
    );
  }

  void _openPanel({bool episodes = false, bool tracks = false, bool danmaku = false, bool chain = false}) {
    setState(() {
      _panelOpen = episodes;
      _tracksOpen = tracks;
      _danmakuOpen = danmaku;
      _chainOpen = chain;
    });
    _pokeControls();
  }

  Future<void> _enterPip() async {
    setState(() => _controlsVisible = false);
    if (!await _mobile!.host.enterPip()) _showOsd('这台设备不能小窗播放', const Duration(seconds: 2));
  }

  /// Upright on a phone: what is playing, the actions, lines and episodes; or the panel that was opened.
  Widget _buildPhoneInfo(BuildContext context) {
    final mobile = _mobile!;
    final caption = [_currentEpisode.name, _lineCaption].join(' · ');
    return PlayerPhoneInfo(
      title: _detail.name,
      caption: caption,
      lines: _detail.lines,
      line: _line,
      episode: _episode,
      showLines: _linesOffered,
      onLine: (i) {
        if (i != _line) _pickLine(i);
      },
      onEpisode: _playEpisode,
      actions: [
        if (_dev) ...[
          PhoneAction(key: const Key('phone-stats'), icon: Icons.query_stats_rounded, label: '统计', selected: _statsOpen, onPressed: _toggleStats),
          PhoneAction(key: const Key('phone-chain'), icon: Icons.account_tree_outlined, label: '链路', onPressed: () => _openPanel(chain: true)),
        ],
        StreamBuilder<double>(
          stream: _player.stream.rate,
          initialData: _player.state.rate,
          builder: (context, snap) => MenuAnchor(
            menuChildren: [
              for (final s in _speeds)
                MenuItemButton(leadingIcon: Icon(s == snap.data ? Icons.check : null, size: 18), onPressed: () => _player.setRate(s), child: Text('${s}x')),
            ],
            builder: (context, controller, _) => PhoneAction(
              icon: Icons.speed_rounded,
              label: snap.data == 1.0 ? '倍速' : '${snap.data}x',
              selected: snap.data != 1.0,
              onPressed: () => controller.isOpen ? controller.close() : controller.open(),
            ),
          ),
        ),
        PhoneAction(
          key: const Key('phone-danmaku-toggle'),
          icon: _danmakuPrefs.enabled ? Icons.chat_bubble_rounded : Icons.chat_bubble_outline_rounded,
          label: _danmakuPrefs.enabled ? '弹幕开' : '弹幕关',
          selected: _danmakuPrefs.enabled,
          onPressed: _toggleDanmaku,
        ),
        PhoneAction(key: const Key('phone-danmaku-panel'), icon: Icons.tune_rounded, label: '弹幕设置', onPressed: () => _openPanel(danmaku: true)),
        PhoneAction(key: const Key('phone-tracks'), icon: Icons.subtitles_outlined, label: '字幕', onPressed: () => _openPanel(tracks: true)),
        if (mobile.pipSupported) PhoneAction(icon: Icons.picture_in_picture_alt_rounded, label: '小窗', onPressed: _enterPip),
      ],
      panel: _tracksOpen
          ? TracksPanel(tracks: _tracks)
          : _chainOpen
          ? PlayerChainPanel(chain: _chain)
          : _danmakuOpen
          ? DanmakuPanel(
              prefs: _danmakuPrefs,
              list: _danmaku,
              loading: _danmakuLoading,
              error: _danmakuError,
              onReload: () => _loadDanmaku(refresh: true),
              onLoadFile: _loadDanmakuFile,
            )
          : null,
      panelTitle: _tracksOpen ? '字幕与音轨' : (_chainOpen ? '链路' : '弹幕'),
      onClosePanel: () => setState(() {
        _tracksOpen = false;
        _danmakuOpen = false;
        _chainOpen = false;
      }),
    );
  }
}

class _TimeLabel extends StatelessWidget {
  const _TimeLabel({required this.player});

  final Player player;

  static String format(Duration d) {
    final h = d.inHours;
    final m = d.inMinutes.remainder(60).toString().padLeft(2, '0');
    final s = d.inSeconds.remainder(60).toString().padLeft(2, '0');
    return h > 0 ? '$h:$m:$s' : '$m:$s';
  }

  @override
  Widget build(BuildContext context) {
    return StreamBuilder<Duration>(
      stream: player.stream.position,
      initialData: player.state.position,
      builder: (context, snap) => Text(
        '${format(snap.data ?? Duration.zero)} / ${format(player.state.duration)}',
        style: const TextStyle(color: Colors.white, fontFeatures: [FontFeature.tabularFigures()]),
      ),
    );
  }
}

/// Mute button plus a volume slider (desktop).
class _VolumeControl extends StatelessWidget {
  const _VolumeControl({required this.player, required this.onToggleMute});

  final Player player;
  final VoidCallback onToggleMute;

  @override
  Widget build(BuildContext context) {
    return StreamBuilder<double>(
      stream: player.stream.volume,
      initialData: player.state.volume,
      builder: (context, snap) {
        final v = (snap.data ?? 100).clamp(0.0, 100.0);
        return Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            IconButton(
              tooltip: v == 0 ? '取消静音 (M)' : '静音 (M)',
              onPressed: onToggleMute,
              icon: Icon(v == 0 ? Icons.volume_off_rounded : (v < 50 ? Icons.volume_down_rounded : Icons.volume_up_rounded), color: Colors.white),
            ),
            SizedBox(
              width: 110,
              child: SliderTheme(
                data: SliderTheme.of(context).copyWith(
                  trackHeight: 3,
                  thumbShape: const RoundSliderThumbShape(enabledThumbRadius: 5),
                  overlayShape: const RoundSliderOverlayShape(overlayRadius: 12),
                  activeTrackColor: Colors.white,
                  inactiveTrackColor: Colors.white24,
                  thumbColor: Colors.white,
                ),
                child: Slider(value: v, max: 100, onChanged: player.setVolume),
              ),
            ),
          ],
        );
      },
    );
  }
}

class _SpeedButton extends StatelessWidget {
  const _SpeedButton({required this.player, required this.speeds});

  final Player player;
  final List<double> speeds;

  @override
  Widget build(BuildContext context) {
    return StreamBuilder<double>(
      stream: player.stream.rate,
      initialData: player.state.rate,
      builder: (context, snap) => MenuAnchor(
        menuChildren: [
          for (final s in speeds)
            MenuItemButton(leadingIcon: Icon(s == snap.data ? Icons.check : null, size: 18), onPressed: () => player.setRate(s), child: Text('${s}x')),
        ],
        builder: (context, controller, _) => TextButton(
          onPressed: () => controller.isOpen ? controller.close() : controller.open(),
          child: Text(snap.data == 1.0 ? '倍速' : '${snap.data}x', style: const TextStyle(color: Colors.white)),
        ),
      ),
    );
  }
}
