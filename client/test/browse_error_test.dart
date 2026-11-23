import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:material_ui/material_ui.dart';
import 'package:tvbox_client/app_state.dart';
import 'package:tvbox_client/engine/engine_client.dart';
import 'package:tvbox_client/main.dart';
import 'package:tvbox_client/ui/device_form.dart';

import 'fake_engine.dart';

void main() {
  testWidgets('a home refresh that fails before the next frame offers its diagnostic and can be retried', (tester) async {
    tester.view.physicalSize = const Size(1400, 900);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    final fallback = fakeEngine(hasConfig: true);
    var homeRequests = 0;
    final engine = MockClient((request) async {
      if (request.url.path.endsWith('/home')) {
        homeRequests++;
        if (homeRequests == 2) {
          return http.Response(
            jsonEncode({
              'error': {
                'code': 'SPIDER_ERROR',
                'message': 'failed',
                'diagnostic': {'stage': 'spider.home', 'kind': 'network', 'site': 'cms', 'cause': 'UnknownHostException'},
              },
            }),
            502,
          );
        }
      }
      final response = await fallback.send(http.Request(request.method, request.url)..bodyBytes = request.bodyBytes);
      return http.Response.fromStream(response);
    });
    final state = AppState(clientFactory: (url) => EngineClient(url, httpClient: engine));
    await tester.pumpWidget(DeviceFormScope.fixed(DeviceForm.desktop, child: TvboxApp(state: state)));
    await tester.pumpAndSettle();
    expect(homeRequests, 1);

    await tester.tap(find.byKey(const Key('home-refresh')));
    await tester.idle();
    await tester.pumpAndSettle();
    expect(tester.takeException(), isNull);
    expect(find.textContaining('加载失败'), findsOneWidget);
    await tester.tap(find.byKey(const Key('source-issue-report')));
    await tester.pumpAndSettle();
    expect(find.textContaining('spider.home', findRichText: true), findsOneWidget);
    await tester.pageBack();
    await tester.pumpAndSettle();

    await tester.tap(find.text('重试'));
    await tester.pumpAndSettle();
    expect(homeRequests, 3);
    expect(find.byKey(const Key('source-issue-report')), findsNothing);
    expect(find.text('推荐'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });
}
