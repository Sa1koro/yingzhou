import 'dart:async';
import 'dart:convert';
import 'dart:io';

import '../diagnostics/diagnostics.dart';
import 'local_engine.dart';

LocalEngine? createLocalEngine() => Platform.isWindows || Platform.isMacOS || Platform.isLinux ? DesktopEngine() : null;

/// Runs `tvbox.engine.server.MainKt` from the engine bundled next to the app:
///
/// - packaged: `<app dir>/engine/lib/*.jar` with a trimmed runtime in `<app dir>/engine/jre`;
/// - development: the repo's `engine/server/build/install/tvbox-engine` (from `./gradlew :server:installDist`),
///   with `JAVA_HOME` or `java` on PATH.
///
/// The engine gets a pipe on stdin and `--exit-with-stdin`, so it ends with the app, even after a crash.
///
/// Everything it prints goes to the diagnostics folder (`engine.log`, see `diagnostics/diagnostics_store_io.dart`),
/// and when it ends without being asked to, a crash record with its last lines is kept, so an engine that dies on
/// start or later leaves a trace.
class DesktopEngine implements LocalEngine {
  /// [diagnostics] is [Diagnostics.instance] when null. Tests point [engineDir] at an engine and add [extraArgs].
  DesktopEngine({this.diagnostics, this.engineDir, this.extraArgs = const []});

  final Diagnostics? diagnostics;
  final Directory? engineDir;
  final List<String> extraArgs;
  Process? _process;
  bool _stopping = false;
  final _output = <String>[];

  Diagnostics get _diag => diagnostics ?? Diagnostics.instance;

  @override
  List<String> get recentOutput => List.unmodifiable(_output);

  /// The process while it runs (tests).
  Process? get process => _process;

  @override
  Future<bool> start(int port, {bool androidJni = false}) async {
    if (_process != null) return true;
    final engineDir = this.engineDir ?? _findEngineDir();
    if (engineDir == null) {
      _remember('找不到内置引擎（engine/lib）');
      _diag.store.engineOutput('找不到内置引擎（engine/lib），应用在 ${Platform.resolvedExecutable}');
      return false;
    }
    final java = _findJava(engineDir);
    final yingxia = Directory('${engineDir.path}${Platform.pathSeparator}yingxia-java');
    final scriptHosts = Directory('${engineDir.path}${Platform.pathSeparator}yingxia-scripts');
    final args = [
      '-Xss1m',
      '-XX:+UseSerialGC',
      // Without these the JVM commits a heap sized from the machine's RAM before doing anything (598 MB on a
      // 32 GB PC); with them the idle engine commits about 93 MB and gives memory back after a burst.
      '-Xms16m',
      '-Xmx1g',
      '-XX:MinHeapFreeRatio=10',
      '-XX:MaxHeapFreeRatio=30',
      // Spider classes converted from dex do not always pass the JVM verifier; Android does not verify them either.
      '-XX:+UnlockDiagnosticVMOptions',
      '-XX:-BytecodeVerificationRemote',
      // Into a pipe Java writes in the system's code page (GBK on Chinese Windows); the app reads UTF-8.
      '-Dstdout.encoding=UTF-8',
      '-Dstderr.encoding=UTF-8',
      '-cp',
      '${engineDir.path}${Platform.pathSeparator}lib${Platform.pathSeparator}*',
      'tvbox.engine.server.MainKt',
      '--port',
      '$port',
      '--exit-with-stdin',
      if (Platform.isWindows && yingxia.existsSync()) ...['--yingxia-host', yingxia.path],
      if (Platform.isWindows && scriptHosts.existsSync()) ...['--yingxia-script-hosts', scriptHosts.path],
      ...extraArgs,
    ];
    _diag.store.engineStarted('$java ${args.join(' ')}');
    try {
      final process = await Process.start(java, args, environment: androidJni ? const {'YINGZHOU_ANDROID_JNI': '1'} : null);
      _process = process;
      _stopping = false;
      const decoder = Utf8Decoder(allowMalformed: true);
      process.stdout.transform(decoder).transform(const LineSplitter()).listen(_print, onError: (_) {});
      process.stderr.transform(decoder).transform(const LineSplitter()).listen(_print, onError: (_) {});
      unawaited(
        process.exitCode.then((code) {
          _remember('引擎已退出（$code）');
          _diag.store.engineExited(code);
          // Asked to stop (or the app is closing): not a crash.
          if (!_stopping && identical(_process, process)) _diag.recordEngineExit(code, _output);
          if (identical(_process, process)) _process = null;
        }),
      );
      // process.stdin stays open for the life of the app; the engine exits when it closes.
      return true;
    } on ProcessException catch (e) {
      _remember('无法启动 Java：${e.message}');
      _diag.store.engineOutput('无法启动 Java（$java）：${e.message}');
      return false;
    }
  }

  @override
  void stop() {
    _stopping = true;
    _process?.kill();
    _process = null;
  }

  void _print(String line) {
    _remember(line);
    _diag.store.engineOutput(line);
  }

  void _remember(String line) {
    _output.add(line);
    if (_output.length > 50) _output.removeAt(0);
  }

  Directory? _findEngineDir() {
    final exeDir = File(Platform.resolvedExecutable).parent;
    final s = Platform.pathSeparator;
    // Windows, Linux: next to the executable (on Linux the real one; /usr/bin/yingzhou is a link to /opt/yingzhou).
    // macOS: Yingzhou.app/Contents/Resources/engine (the exe is in Contents/MacOS).
    for (final packaged in [Directory('${exeDir.path}${s}engine'), Directory('${exeDir.parent.path}${s}Resources${s}engine')]) {
      if (Directory('${packaged.path}${s}lib').existsSync()) return packaged;
    }
    // Development: walk up to the repo and use the Gradle distribution.
    var dir = exeDir;
    for (var i = 0; i < 10; i++) {
      final dev = Directory([dir.path, 'engine', 'server', 'build', 'install', 'tvbox-engine'].join(Platform.pathSeparator));
      if (Directory('${dev.path}${Platform.pathSeparator}lib').existsSync()) return dev;
      final parent = dir.parent;
      if (parent.path == dir.path) break;
      dir = parent;
    }
    return null;
  }

  /// The bundled runtime first; `javaw` on Windows so no console window flashes up.
  String _findJava(Directory engineDir) {
    final exe = Platform.isWindows ? 'javaw.exe' : 'java';
    final s = Platform.pathSeparator;
    final bundled = File('${engineDir.path}${s}jre${s}bin$s$exe');
    if (bundled.existsSync()) return bundled.path;
    final home = Platform.environment['JAVA_HOME'];
    if (home != null && File('$home${s}bin$s$exe').existsSync()) return '$home${s}bin$s$exe';
    return Platform.isWindows ? 'javaw' : 'java';
  }
}
