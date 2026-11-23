import 'dart:convert';

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:material_ui/material_ui.dart';
import 'package:tvbox_client/app_state.dart';
import 'package:tvbox_client/diagnostics/dev_log.dart';
import 'package:tvbox_client/diagnostics/redact.dart';
import 'package:tvbox_client/engine/engine_client.dart';
import 'package:tvbox_client/engine/models.dart';
import 'package:tvbox_client/engine/source_diagnostic.dart';
import 'package:tvbox_client/engine/vod_models.dart';
import 'package:tvbox_client/main.dart';
import 'package:tvbox_client/pages/player_dev.dart';
import 'package:tvbox_client/ui/device_form.dart';

import 'fake_engine.dart';

const _issue = {
  'stage': 'spider.recommend',
  'site': 'cms',
  'kind': 'missing_class',
  'cause': 'NoClassDefFoundError: android.content.ContextWrapper',
  'archive': '123456789abc',
  'initCause': 'UnsatisfiedLinkError',
};

http.Response _json(Object object, [int status = 200]) => http.Response.bytes(utf8.encode(jsonEncode(object)), status);

void main() {
  final base = Uri.parse('http://127.0.0.1:9978');

  test('shared diagnostics omit arbitrary exception text, header values, addresses and absolute paths', () {
    final issue = SourceDiagnostic.tryParse({..._issue, 'cause': 'IllegalStateException: this text is private', 'site': 'guest@example.org'})!;
    expect(issue.cause, 'IllegalStateException');
    expect(issue.site, isEmpty);
    final log = DevLog()
      ..warn('站点', '任意异常原文', detail: {'请求头': 'Private-Header: arbitrary-value\nCookie: foo=private', '播放地址': 'http://127.0.0.1:9978/private?arg=private'});
    final out = Redactor().forSharing('${log.entries.single.shareLine}\nfile C:\\cache dir\\private.txt\n/var/private/a.txt\nuser@example.org');
    for (final private in ['Header', '任意异常原文', 'arbitrary-value', 'Cookie', 'private', 'example.org', '127.0.0.1', 'cache dir']) {
      expect(out, isNot(contains(private)));
    }
  });

  test('unstructured engine output and copied playback chains omit raw third-party content', () {
    final shared = Redactor().sharedLog('Cookie: sensitive\nAuthorization: Bearer arbitrary\nguest fault: arbitrary\njava.net.SocketException: arbitrary');
    expect(shared, contains('SocketException'));
    expect(shared, isNot(contains('arbitrary')));
    expect(shared, isNot(contains('sensitive')));
    expect(Redactor().sharedError('IllegalStateException: arbitrary text'), 'IllegalStateException');
    const chain = PlayChain(
      subscription: 'private subscription',
      siteName: 'private site',
      siteKey: 'cms',
      runtime: 'jar',
      title: 'private title',
      vodId: 'private id',
      lineName: 'private line',
      lineNumber: 1,
      lineCount: 2,
      episodeName: 'private episode',
      episodeId: 'private id',
      played: PlayInfo(kind: 'direct', url: 'private address', headers: {'Account': 'private header'}, cache: 'private cache'),
      onRelay: false,
      parser: 'private parser',
      resolveMs: 100,
      firstFrameMs: null,
      failed: [(detail: 'private failure', shareSummary: '第 1 条 · 解析失败')],
    );
    expect(chain.toText(), contains('第 1 条 · 解析失败'));
    expect(chain.toText(), contains('1 个请求头'));
    expect(chain.toText(), isNot(contains('private')));
  });

  test('configuration, native JNI and missing spiders keep their distinct public causes', () {
    SourceDiagnostic issue(String stage, String kind, String cause) => SourceDiagnostic.tryParse({'stage': stage, 'kind': kind, 'cause': cause})!;
    expect(issue('config.fetch', 'network', 'UnknownHostException').summary, '获取订阅 · 域名解析失败（DNS）');
    expect(issue('config.fetch', 'network', 'IOException: HTTP 504').summary, '获取订阅 · 上游返回 HTTP 504');
    expect(issue('config.parse', 'invalid_result', 'InvalidConfigException: subscription returned a web page').summary, '解析订阅配置 · 订阅地址返回了网页');
    expect(issue('spider.create', 'missing_spider', 'ClassNotFoundException: com.github.catvod.spider.X').kindLabel, '配置指定的蜘蛛类缺失');
    expect(issue('spider.init', 'native', 'UnsatisfiedLinkError: Android JNI: guest timed out').report, contains('Android JNI: guest timed out'));
    expect(issue('spider.create', 'native', 'UnsatisfiedLinkError: Android JNI: native method not bound').kindLabel, '找不到该 JNI 方法的绑定');
    expect(issue('spider.init', 'native', 'UnsatisfiedLinkError: Android JNI: guest timed out at secret').report, isNot(contains('secret')));
  });

  test('an initializer wrapper keeps the site exception and explains the recorded native phase', () {
    SourceDiagnostic issue(String cause, String initCause) =>
        SourceDiagnostic.tryParse({'site': 'DouBan', 'stage': 'spider.create', 'kind': 'native', 'cause': cause, 'initCause': initCause})!;
    const wrapper = 'ExceptionInInitializerError';
    const missing = 'UnsatisfiedLinkError: Android JNI: native dependency missing';
    final recorded = issue(wrapper, missing);
    expect(recorded.summary, '创建站点蜘蛛 · 共享蜘蛛包：缺少原生库依赖');
    expect(recorded.details['站点异常'], wrapper);
    expect(recorded.details['共享包首次初始化'], missing);
    expect(issue('UnsatisfiedLinkError: Android JNI: JNI_OnLoad failed', missing).kindLabel, '原生库在 JNI_OnLoad 初始化时失败');
    expect(issue(wrapper, '$missing https://example.invalid/?secret').report, isNot(contains('secret')));
    expect(issue(wrapper, '$missing extra text').kindLabel, '原生库调用失败');
  });

  test('home warnings, HTTP errors and streamed search use the same source report', () async {
    final log = DevLog();
    final client = EngineClient(
      base,
      httpClient: MockClient((request) async {
        if (request.url.path.endsWith('/home')) {
          return _json({'list': [], 'classes': [], 'warning': _issue});
        }
        if (request.url.path.endsWith('/stream')) {
          return http.Response(
            'event: site\ndata: ${jsonEncode({
              'site': 'cms',
              'status': 'error',
              'diagnostic': {..._issue, 'stage': 'spider.search'},
            })}\n\n',
            200,
          );
        }
        return _json({
          'error': {
            'code': 'SPIDER_ERROR',
            'message': 'legacy message',
            'diagnostic': {..._issue, 'stage': 'spider.create'},
          },
        }, 502);
      }),
    )..onSourceIssue = log.sourceIssue;
    expect((await client.home('cms')).warning?.stage, 'spider.recommend');
    await expectLater(client.detail('cms', '1'), throwsA(isA<EngineException>().having((error) => error.diagnostic?.stage, 'stage', 'spider.create')));
    final search = await client.searchAll('片名').where((event) => event is SearchSiteStatus).first as SearchSiteStatus;
    expect(search.diagnostic?.stageLabel, '搜索站点');
    expect(log.entries.map((entry) => entry.level), [DevLevel.warn, DevLevel.error, DevLevel.error]);
    expect(log.recentSourceIssues.first, contains('spider.search'));
    expect(log.recentSourceIssues.last, contains('123456789abc'));
  });

  test('developer source log keeps the first JNI callback and rejects raw messages', () {
    const signature = 'JNI callback android/app/Application->getCodeCacheDir()Ljava/io/File;';
    final issue = SourceDiagnostic.tryParse({
      ..._issue,
      'stage': 'spider.create',
      'kind': 'native',
      'cause': 'UnsatisfiedLinkError: Android JNI: guest unavailable',
      'initCause': 'UnsatisfiedLinkError: Android JNI: unsupported Java callback',
      'nativeOperation': '$signature /private/data?token=secret',
      'initNativeOperation': signature,
    })!;
    final log = DevLog()..sourceIssue(issue, warning: false);
    expect(issue.kindLabel, '共享蜘蛛包：Android 原生代码需要的 Java 回调尚不支持');
    expect(issue.details['共享包首次失败的 JNI 调用'], signature);
    expect(log.recentSourceIssues.single, contains(signature));
    expect(issue.report, isNot(contains('secret')));
  });

  test('a legacy empty home and unrecognized diagnostics do not produce a fault', () async {
    final log = DevLog();
    final client = EngineClient(
      base,
      httpClient: MockClient((request) async {
        if (request.url.path.endsWith('/home')) return _json({'classes': [], 'list': []});
        return _json({
          'error': {
            'code': 'SITE_ERROR',
            'message': 'existing error',
            'diagnostic': ['unknown format'],
          },
        }, 502);
      }),
    )..onSourceIssue = log.sourceIssue;
    expect((await client.home('cms')).warning, isNull);
    await expectLater(client.detail('cms', '1'), throwsA(isA<EngineException>().having((error) => error.message, 'legacy message', 'existing error')));
    expect(log.recentSourceIssues, isEmpty);
  });

  test('repeated config reads preserve the cached subscription and record a refresh warning once', () async {
    final log = DevLog();
    final engine = EngineClient(
      base,
      httpClient: MockClient(
        (_) async => _json({
          'subscriptionId': 'sub',
          'url': 'https://private.invalid/s',
          'repos': [],
          'sites': 3,
          'parses': 1,
          'lives': 0,
          'stored': true,
          'refreshDiagnostic': {'stage': 'config.fetch', 'kind': 'network', 'cause': 'IOException: HTTP 504'},
        }),
      ),
    )..onSourceIssue = log.sourceIssue;
    expect((await engine.config()).sites, 3);
    expect((await engine.config()).refreshDiagnostic?.summary, '获取订阅 · 上游返回 HTTP 504');
    expect(log.entries.single.level, DevLevel.warn);
    expect(log.recentSourceIssues.single, isNot(contains('private')));
  });

  test('subscription activation and explicit refresh keep the active config and expose the upstream failure', () async {
    final fallback = fakeEngine(hasConfig: true);
    final engine = MockClient((request) async {
      final path = request.url.path;
      if (path.endsWith('/activate') || path.endsWith('/reload')) {
        return _json({
          'error': {
            'code': 'CONFIG_LOAD_FAILED', 'message': 'https://private.invalid?key=secret',
            'diagnostic': {'stage': 'config.fetch', 'kind': 'network', 'cause': path.endsWith('/activate') ? 'UnknownHostException' : 'IOException: HTTP 504'},
          },
        }, 502);
      }
      return http.Response.fromStream(await fallback.send(http.Request(request.method, request.url)..bodyBytes = request.bodyBytes));
    });
    final state = AppState(clientFactory: (url) => EngineClient(url, httpClient: engine));
    await state.start();
    final active = state.summary!.subscriptionId;
    await state.useSubscription('new');
    expect(state.error, '获取订阅 · 域名解析失败（DNS）');
    expect(state.summary!.subscriptionId, active);
    await state.refreshSubscription();
    expect(state.error, '获取订阅 · 上游返回 HTTP 504');
    expect(state.actionDiagnostic!.report, isNot(contains('secret')));
    expect(state.summary!.subscriptionId, active);
  });

  testWidgets('a partial home keeps categories and provides a readable, redacted, copyable report', (tester) async {
    tester.view.physicalSize = const Size(1400, 900);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    final fallback = fakeEngine(hasConfig: true);
    final engine = MockClient((request) async {
      if (request.url.path.endsWith('/home')) {
        return _json({
          'classes': [
            {'id': '1', 'name': '电影'},
          ],
          'list': [],
          'warning': {..._issue, 'cause': 'NoClassDefFoundError https://example.org/private?token=entrysecret'},
        });
      }
      final response = await fallback.send(http.Request(request.method, request.url)..bodyBytes = request.bodyBytes);
      return http.Response.fromStream(response);
    });
    String? copied;
    final messenger = tester.binding.defaultBinaryMessenger;
    messenger.setMockMethodCallHandler(SystemChannels.platform, (call) async {
      if (call.method == 'Clipboard.setData') copied = (call.arguments as Map)['text'] as String;
      return null;
    });
    addTearDown(() => messenger.setMockMethodCallHandler(SystemChannels.platform, null));
    await tester.pumpWidget(
      DeviceFormScope.fixed(
        DeviceForm.desktop,
        child: TvboxApp(
          state: AppState(clientFactory: (url) => EngineClient(url, httpClient: engine)),
        ),
      ),
    );
    await tester.pumpAndSettle();
    expect(find.text('电影'), findsOneWidget);
    expect(find.text('读取首页推荐 · 找不到所需的类'), findsOneWidget);
    await tester.tap(find.byKey(const Key('source-issue-report')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('diag-text-copy')));
    await tester.pumpAndSettle();
    expect(copied, contains('spider.recommend'));
    expect(copied, contains('蜘蛛包缓存标识：123456789abc'));
    expect(copied, contains('共享包首次初始化：UnsatisfiedLinkError'));
    expect(copied, isNot(contains('example.org')));
    expect(copied, isNot(contains('entrysecret')));
  });
}
