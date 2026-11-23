import 'dart:convert';

import 'package:http/http.dart' as http;

import 'library_models.dart';
import 'live_models.dart';
import 'models.dart';
import 'network_models.dart';
import 'source_diagnostic.dart';
import 'stream_cache_models.dart';
import 'vod_models.dart';

/// Client for engine protocol v1. The same client talks to the built-in engine and to remote ones.
class EngineClient {
  EngineClient(this.baseUrl, {http.Client? httpClient, this.token}) : _http = httpClient ?? http.Client();

  /// e.g. `http://127.0.0.1:9978`
  final Uri baseUrl;

  /// The token from pairing with a remote engine (`Authorization: Bearer`); null for the engine on this machine.
  String? token;

  /// Called when the engine refuses the token (none, wrong or revoked): the app goes back to pairing.
  void Function(EngineException error)? onUnauthorized;
  void Function(SourceDiagnostic issue, {required bool warning})? onSourceIssue;
  final http.Client _http;

  static const timeout = Duration(seconds: 60);

  // ---- engine and subscriptions ----

  Future<EngineInfo> info() async => EngineInfo.fromJson(await _send('GET', 'info'));

  Future<List<Subscription>> subscriptions() async => [
    for (final s in await _send('GET', 'subscriptions') as List<dynamic>) Subscription.fromJson(s as Map<String, dynamic>),
  ];

  Future<Subscription> addSubscription(String url, {String? name}) async =>
      Subscription.fromJson(await _send('POST', 'subscriptions', body: {'url': url, if (name != null && name.isNotEmpty) 'name': name}));

  Future<void> removeSubscription(String id) => _send('DELETE', 'subscriptions/$id');

  /// Names a saved subscription; an empty [name] goes back to its address.
  Future<Subscription> renameSubscription(String id, String name) async =>
      Subscription.fromJson(await _send('PUT', 'subscriptions/$id', body: {'name': name}));

  /// Loads a subscription. For a multi-repository subscription, [repo] picks the entry (default: first).
  Future<ConfigSummary> activate(String id, {String? repo}) async =>
      _configResult(await _send('POST', 'subscriptions/$id/activate', body: {'repo': ?repo}));

  Future<ConfigSummary> config() async => _configResult(await _send('GET', 'config'));

  (String, String, String)? _lastRefreshWarning;

  ConfigSummary _configResult(Map<String, dynamic> json) {
    final result = ConfigSummary.fromJson(json);
    final warning = result.refreshDiagnostic;
    final key = warning == null ? null : (result.subscriptionId, result.url, warning.report);
    if (warning != null && key != _lastRefreshWarning) onSourceIssue?.call(warning, warning: true);
    _lastRefreshWarning = key;
    return result;
  }

  Future<EngineSettings> settings() async => EngineSettings.fromJson(await _send('GET', 'settings'));

  Future<EngineSettings> updateSettings(EngineSettings settings) async =>
      EngineSettings.fromJson(await _send('PUT', 'settings', body: settings.toJson()));

  /// The DoH servers to choose from and the active config's hosts / headers / ads.
  Future<NetworkInfo> network() async => NetworkInfo.fromJson(await _send('GET', 'network'));
  /// Changes only the settings named in [fields]; the engine keeps the others as they are.
  Future<EngineSettings> patchSettings(Map<String, Object?> fields) async => EngineSettings.fromJson(await _send('PUT', 'settings', body: fields));

  // ---- the engine's stream cache ----

  Future<StreamCacheInfo> streamCache() async => StreamCacheInfo.fromJson(await _send('GET', 'stream-cache'));

  Future<StreamCacheInfo> clearStreamCache() async => StreamCacheInfo.fromJson(await _send('DELETE', 'stream-cache'));

  /// Which parts of the film behind `PlayInfo.cache` the engine holds.
  Future<StreamCoverage> streamCoverage(String key) async => StreamCoverage.fromJson(await _send('GET', 'stream-cache/coverage', query: {'key': key}));

  Future<List<SiteInfo>> sites() async => [for (final s in await _send('GET', 'sites') as List<dynamic>) SiteInfo.fromJson(s as Map<String, dynamic>)];

  // ---- browsing ----

  // With [refresh] the engine asks the site now instead of answering from what it kept.

  Future<HomeResult> home(String site, {bool refresh = false}) async {
    final home = HomeResult.fromJson(await _send('GET', 'sites/${_seg(site)}/home', query: {if (refresh) 'refresh': '1'}));
    if (home.warning case final issue?) onSourceIssue?.call(issue, warning: true);
    return home;
  }

  Future<VodPage> category(String site, String classId, {int page = 1, Map<String, String> filters = const {}, bool refresh = false}) async =>
      VodPage.fromJson(
        await _send(
          'GET',
          'sites/${_seg(site)}/category',
          query: {'tid': classId, 'pg': '$page', if (filters.isNotEmpty) 'f': jsonEncode(filters), if (refresh) 'refresh': '1'},
        ),
      );

  Future<VodDetail> detail(String site, String id, {bool refresh = false}) async =>
      VodDetail.fromJson(await _send('GET', 'sites/${_seg(site)}/detail', query: {'id': id, if (refresh) 'refresh': '1'}));

  /// Downloads the active config again; throws when that fails (the config in use stays).
  Future<ConfigSummary> reloadConfig() async => _configResult(await _send('POST', 'config/reload'));

  Future<CacheInfo> cacheInfo() async => CacheInfo.fromJson(await _send('GET', 'cache'));

  Future<CacheInfo> clearCache() async => CacheInfo.fromJson(await _send('DELETE', 'cache'));

  Future<VodPage> search(String site, String keyword, {int page = 1}) async =>
      VodPage.fromJson(await _send('GET', 'sites/${_seg(site)}/search', query: {'wd': keyword, 'pg': '$page'}));

  /// [direct]: the player will fetch the video from the source itself (see [PlayInfo.relay]); older engines ignore it.
  Future<PlayInfo> resolvePlay(String site, String line, String episodeId, {bool direct = false, bool offerDriveSignIn = false}) async => PlayInfo.fromJson(
    await _send('POST', 'play/resolve', body: {'site': site, 'line': line, 'episodeId': episodeId, if (direct) 'direct': true, if (offerDriveSignIn) 'offerDriveSignIn': true}),
  );

  Future<String> pollDriveSignIn(String site, String handle) async {
    final reply = await _send('POST', 'play/drive-sign-in/poll', body: {'site': site, 'handle': handle}) as Map<String, dynamic>;
    return reply['status'] as String;
  }

  // ---- library ----

  Future<List<HistoryEntry>> history({int limit = 100}) async => [
    for (final h in await _send('GET', 'history', query: {'limit': '$limit'}) as List<dynamic>) HistoryEntry.fromJson(h as Map<String, dynamic>),
  ];

  /// Null when the video has no history.
  Future<HistoryEntry?> historyFor(String site, String vodId) async {
    try {
      return HistoryEntry.fromJson(await _send('GET', 'history/${_seg(site)}/${_seg(vodId)}'));
    } on EngineException catch (e) {
      if (e.statusCode == 404) return null;
      rethrow;
    }
  }

  Future<void> putHistory(HistoryEntry entry) => _send('PUT', 'history/${_seg(entry.site)}/${_seg(entry.vodId)}', body: entry.toJson());

  Future<void> removeHistory(String site, String vodId) => _send('DELETE', 'history/${_seg(site)}/${_seg(vodId)}');

  Future<void> clearHistory() => _send('DELETE', 'history');

  // ---- live TV ----

  Future<List<LiveSourceInfo>> liveSources() async => [for (final s in await _send('GET', 'live') as List) LiveSourceInfo.fromJson(s as Map<String, dynamic>)];

  Future<LiveList> liveList(int source, {bool refresh = false}) async =>
      LiveList.fromJson(await _send('GET', 'live/$source', query: refresh ? {'refresh': '1'} : null));

  Future<EpgDay> epg(int source, String channel, {String? date}) async =>
      EpgDay.fromJson(await _send('GET', 'live/$source/epg', query: {'channel': channel, 'date': ?date}));

  /// The address of what [channel] showed from [start] to [end] (回看), [line] first.
  Future<LiveCatchup> liveCatchup(int source, String channel, {String? group, int line = 0, required DateTime start, required DateTime end}) async =>
      LiveCatchup.fromJson(
        await _send(
          'GET',
          'live/$source/catchup',
          query: {
            'channel': channel,
            'group': ?group,
            'line': '$line',
            'start': '${start.millisecondsSinceEpoch}',
            'end': '${end.millisecondsSinceEpoch}',
          },
        ),
      );

  Future<List<FavoriteEntry>> favorites() async => [
    for (final f in await _send('GET', 'favorites') as List<dynamic>) FavoriteEntry.fromJson(f as Map<String, dynamic>),
  ];

  Future<bool> isFavorite(String site, String vodId) async {
    try {
      await _send('GET', 'favorites/${_seg(site)}/${_seg(vodId)}');
      return true;
    } on EngineException catch (e) {
      if (e.statusCode == 404) return false;
      rethrow;
    }
  }

  Future<void> putFavorite(FavoriteEntry entry) => _send('PUT', 'favorites/${_seg(entry.site)}/${_seg(entry.vodId)}', body: entry.toJson());

  Future<void> removeFavorite(String site, String vodId) => _send('DELETE', 'favorites/${_seg(site)}/${_seg(vodId)}');

  /// Aggregated search over every searchable site, streamed as each site answers (SSE).
  Stream<SearchEvent> searchAll(String keyword, {Set<String>? sites}) async* {
    final request = http.Request('GET', _uri('search/stream', {'wd': keyword, if (sites != null && sites.isNotEmpty) 'sites': sites.join(',')}));
    request.headers['Accept'] = 'text/event-stream';
    _auth(request);

    final http.StreamedResponse response;
    try {
      response = await _http.send(request).timeout(timeout);
    } on Exception catch (e) {
      throw EngineException('UNREACHABLE', '无法连接引擎：$e');
    }
    if (response.statusCode != 200) {
      throw _error(response.statusCode, await response.stream.bytesToString());
    }

    String? event;
    final data = StringBuffer();
    await for (final line in response.stream.transform(utf8.decoder).transform(const LineSplitter())) {
      if (line.isEmpty) {
        if (event != null && data.isNotEmpty) {
          final json = jsonDecode(data.toString()) as Map<String, dynamic>;
          switch (event) {
            case 'site':
              final status = SearchSiteStatus.fromJson(json);
              if (status.status == 'error' && status.diagnostic != null) onSourceIssue?.call(status.diagnostic!, warning: false);
              yield status;
            case 'results':
              yield SearchResults.fromJson(json);
            case 'end':
              yield SearchEnd.fromJson(json);
          }
        }
        event = null;
        data.clear();
      } else if (line.startsWith('event:')) {
        event = line.substring(6).trim();
      } else if (line.startsWith('data:')) {
        data.write(line.substring(5).trim());
      }
    }
  }

  void close() => _http.close();

  // ---- plumbing ----

  String _seg(String s) => Uri.encodeComponent(s);

  Uri _uri(String path, [Map<String, String>? query]) {
    final u = baseUrl.resolve('/api/v1/$path');
    return query == null || query.isEmpty ? u : u.replace(queryParameters: query);
  }

  void _auth(http.BaseRequest request) {
    final t = token;
    if (t != null && t.isNotEmpty) request.headers['Authorization'] = 'Bearer $t';
  }

  /// A request to any `/api/v1/<path>`, for API groups kept in their own files (see `lan.dart`).
  Future<dynamic> send(String method, String path, {Object? body, Map<String, String>? query}) => _send(method, path, body: body, query: query);

  Future<dynamic> _send(String method, String path, {Object? body, Map<String, String>? query}) async {
    final request = http.Request(method, _uri(path, query));
    _auth(request);
    if (body != null) {
      request.headers['Content-Type'] = 'application/json';
      request.body = jsonEncode(body);
    }

    final http.Response response;
    try {
      response = await http.Response.fromStream(await _http.send(request).timeout(timeout));
    } on Exception catch (e) {
      throw EngineException('UNREACHABLE', '无法连接引擎：$e');
    }

    final text = utf8.decode(response.bodyBytes);
    if (response.statusCode >= 200 && response.statusCode < 300) {
      return text.isEmpty ? null : jsonDecode(text);
    }
    throw _error(response.statusCode, text);
  }

  EngineException _error(int status, String text) {
    final e = _parseError(status, text);
    if (e.diagnostic case final issue?) onSourceIssue?.call(issue, warning: false);
    if (status == 401) onUnauthorized?.call(e);
    return e;
  }

  EngineException _parseError(int status, String text) {
    try {
      final error = (jsonDecode(text) as Map<String, dynamic>)['error'] as Map<String, dynamic>;
      return EngineException(error['code'] as String, error['message'] as String, statusCode: status, diagnostic: SourceDiagnostic.tryParse(error['diagnostic']));
    } catch (_) {
      return EngineException('HTTP_$status', text, statusCode: status);
    }
  }
}
