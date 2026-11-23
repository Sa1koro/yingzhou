import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:flutter/gestures.dart';
import 'package:material_ui/material_ui.dart';
import 'package:media_kit/media_kit.dart';
import 'package:media_kit_video/media_kit_video.dart';
import 'package:tvbox_client/app_state.dart';
import 'package:tvbox_client/engine/engine_client.dart';
import 'package:tvbox_client/engine/vod_models.dart';
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
  final output = File(arguments.single);
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
    await state.connect();
    await state.importSubscription(fixtureUrl);
    for (final kind in ['MP4', 'HLS']) {
      final key = 'local-$kind';
      final home = await state.client.home(key);
      final card = home.list.single;
      final detail = await state.client.detail(key, card.id);
      backend = Completer<PlayerBackend>();
      final clock = Stopwatch()..start();
      final route = MaterialPageRoute<void>(builder: (_) => PlayerPage(detail: detail, line: 0, episode: 0));
      unawaited(navigator.currentState!.push(route));
      final media = await backend.future.timeout(const Duration(seconds: 10));
      await (media.view as Video).controller.waitUntilFirstFrameRendered.timeout(const Duration(seconds: 30));
      final firstFrameMs = clock.elapsedMilliseconds;
      final player = media.player;
      await _until(() => player.state.position >= const Duration(seconds: 1) && player.state.duration > Duration.zero);
      if (player.state.position > const Duration(seconds: 10)) throw StateError('Media started from saved position');
      final seek = _findSeekBar(tree.currentContext! as Element) ?? (throw StateError('Seek bar is absent'));
      final box = seek.renderObject! as RenderBox;
      final left = box.localToGlobal(Offset(SeekBar.inset + 5, box.size.height / 2));
      final middle = box.localToGlobal(box.size.center(Offset.zero));
      final pointer = kind == 'MP4' ? 120 : 121;
      final seekClock = Stopwatch()..start();
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
    exit(0);
  } catch (error) {
    output.writeAsStringSync(jsonEncode({'status': 'failed', 'reason': error.runtimeType.toString(), 'lines': results}));
    exit(1);
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
