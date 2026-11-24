import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:flutter/gestures.dart';
import 'package:material_ui/material_ui.dart';
import 'package:media_kit/media_kit.dart';
import 'package:media_kit_video/media_kit_video.dart';
import 'package:tvbox_client/app_state.dart';
import 'package:tvbox_client/engine/engine_client.dart';
import 'package:tvbox_client/engine/local_engine_io.dart';
import 'package:tvbox_client/pages/player_page.dart';
import 'package:tvbox_client/pages/seek_bar.dart';
import 'package:tvbox_client/player_backend.dart';
import 'package:tvbox_client/ui/device_form.dart';
import 'package:tvbox_client/ui/theme.dart';

const engineUrl = 'http://127.0.0.1:9978';
const fixtureUrl = 'http://127.0.0.1:19979/config.json';

Future<void> main(List<String> arguments) async {
  final binding = WidgetsFlutterBinding.ensureInitialized();
  MediaKit.ensureInitialized();
  final output = File(arguments.first);
  final hosted = arguments.contains('--yingxia-demo');
  final bundledEngine = arguments.contains('--bundled-engine')
      ? DesktopEngine(extraArgs: ['--data', '${output.path}.engine'])
      : null;
  var stage = 'connect';
  final tree = GlobalKey();
  final navigator = GlobalKey<NavigatorState>();
  var backend = Completer<PlayerBackend>();
  final state = AppState(
    clientFactory: EngineClient.new,
    engineUrl: engineUrl,
    playerBackend: () {
      final player = mediaKitBackend();
      backend.complete(player);
      return player;
    },
  );
  runApp(DeviceFormScope.fixed(
    DeviceForm.desktop,
    child: AppScope(
      state: state,
      child: MaterialApp(
        navigatorKey: navigator,
        theme: buildDarkTheme(),
        home: const Scaffold(body: Center(child: Text('本地媒体验收'))),
        builder: (context, child) => KeyedSubtree(key: tree, child: child!),
      ),
    ),
  ));
  final results = <Map<String, Object>>[];
  try {
    await binding.endOfFrame;
    if (bundledEngine != null) {
      stage = 'start bundled engine';
      if (!await bundledEngine.start(9978)) throw StateError('Bundled engine did not start');
      final client = HttpClient();
      try {
        await _untilAsync(() async {
          try {
            final response = await (await client.getUrl(Uri.parse('$engineUrl/api/v1/info'))).close();
            await response.drain<void>();
            return response.statusCode == 200;
          } on SocketException {
            return false;
          }
        });
      } finally {
        client.close(force: true);
      }
    }
    await state.connect();
    stage = 'import';
    await state.importSubscription(hosted ? fixtureUrl.replaceFirst('config.json', 'config-jar.json') : fixtureUrl);
    for (final kind in hosted ? ['HLS', 'MP4'] : ['MP4', 'HLS']) {
      final key = hosted ? 'demo_jar' : 'local-$kind';
      stage = '$kind home';
      final home = await state.client.home(key);
      if (home.list.isEmpty) throw StateError('Demo home returned no playable selection');
      stage = '$kind detail';
      final detail = await state.client.detail(key, home.list.single.id);
      backend = Completer<PlayerBackend>();
      final clock = Stopwatch()..start();
      final route = MaterialPageRoute<void>(builder: (_) => PlayerPage(detail: detail, line: 0, episode: hosted && kind == 'MP4' ? 1 : 0));
      unawaited(navigator.currentState!.push(route));
      stage = '$kind first frame';
      final media = await backend.future.timeout(const Duration(seconds: 10));
      await (media.view as Video).controller.waitUntilFirstFrameRendered.timeout(const Duration(seconds: 30));
      final firstFrameMs = clock.elapsedMilliseconds;
      final player = media.player;
      stage = '$kind initial playback';
      await _until(() => player.state.position >= const Duration(seconds: 1) && player.state.duration > Duration.zero);
      if (player.state.position > const Duration(seconds: 10)) throw StateError('Media started from saved position');
      final seek = _findSeekBar(tree.currentContext! as Element) ?? (throw StateError('Seek bar is absent'));
      final box = seek.renderObject! as RenderBox;
      final left = box.localToGlobal(Offset(SeekBar.inset + 5, box.size.height / 2));
      final middle = box.localToGlobal(box.size.center(Offset.zero));
      final pointer = kind == 'MP4' ? 120 : 121;
      final seekClock = Stopwatch()..start();
      stage = '$kind seek';
      binding.handlePointerEvent(PointerDownEvent(pointer: pointer, position: left));
      binding.handlePointerEvent(PointerMoveEvent(pointer: pointer, position: middle, delta: middle - left, buttons: kPrimaryButton));
      binding.handlePointerEvent(PointerUpEvent(pointer: pointer, position: middle));
      final target = player.state.duration.inMilliseconds ~/ 2;
      await _until(() => (player.state.position.inMilliseconds - target).abs() < 4000);
      final landed = player.state.position;
      await _until(() => player.state.playing && !player.state.buffering && player.state.position > landed + const Duration(seconds: 1));
      results.add({'media': kind, 'firstFrameMs': firstFrameMs, 'targetMs': target, 'landedMs': landed.inMilliseconds,
        'resumedMs': player.state.position.inMilliseconds, 'dragToResumeMs': seekClock.elapsedMilliseconds});
      navigator.currentState!.pop();
      await route.completed;
    }
    output.writeAsStringSync(jsonEncode({'status': 'passed', 'lines': results}));
    bundledEngine?.stop();
    exit(0);
  } catch (error) {
    output.writeAsStringSync(jsonEncode({'status': 'failed', 'stage': stage, 'reason': error.runtimeType.toString(), 'lines': results}));
    bundledEngine?.stop();
    exit(1);
  }
}

Future<void> _untilAsync(Future<bool> Function() condition) async {
  final clock = Stopwatch()..start();
  while (!await condition()) {
    if (clock.elapsed > const Duration(seconds: 15)) throw TimeoutException('Bundled engine did not become ready');
    await Future<void>.delayed(const Duration(milliseconds: 100));
  }
}

Future<void> _until(bool Function() condition) async {
  final clock = Stopwatch()..start();
  while (!condition()) {
    if (clock.elapsed > const Duration(seconds: 25)) throw TimeoutException('Player state did not advance');
    await Future<void>.delayed(const Duration(milliseconds: 75));
  }
}

Element? _findSeekBar(Element element) {
  if (element.widget is SeekBar && (element.widget as SeekBar).duration > Duration.zero) return element;
  Element? found;
  element.visitChildren((child) => found ??= _findSeekBar(child));
  return found;
}
