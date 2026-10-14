import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:pointly/channel/pointly_channel.dart';
import 'package:pointly/l10n/strings.dart';
import 'package:pointly/main.dart';
import 'package:pointly/screens/home_screen.dart';
import 'package:pointly/screens/metrics_screen.dart';
import 'package:pointly/screens/onboarding_screen.dart';
import 'package:pointly/screens/permissions_screen.dart';
import 'package:pointly/screens/settings_screen.dart';

class FakeCore {
  final calls = <MethodCall>[];
  bool serviceOn = true;
  bool bubbleOn = false;
  bool mic = false;
  bool notifications = false;
  Map<String, Object?> config = {
    'baseUrl': 'http://10.0.2.2:8000',
    'appKey': 'change-me',
    'voiceIn': 'device',
    'voiceOut': 'device',
    'language': 'en',
    'talkMode': 'hold',
    'demoMode': false,
    'debugBoxes': false,
    'onboarded': false,
  };
  Map<String, Object?> summary = {};
  Map<String, Object?> check = {'reachable': true, 'model': 'gemini-3.8-flash', 'keyOk': true};
  final events = StreamController<Map<String, Object?>>.broadcast();

  List<String> get methods => calls.map((c) => c.method).toList();

  MethodCall last(String method) => calls.lastWhere((c) => c.method == method);

  Future<Object?> handle(MethodCall call) async {
    calls.add(call);
    switch (call.method) {
      case 'isServiceEnabled':
        return serviceOn;
      case 'isBubbleVisible':
        return bubbleOn;
      case 'getPermissions':
        return {'accessibility': serviceOn, 'microphone': mic, 'notifications': notifications};
      case 'getConfig':
        return config;
      case 'setConfig':
        config = {...config, ...(call.arguments as Map).cast<String, Object?>()};
        return null;
      case 'showBubble':
        bubbleOn = true;
        return null;
      case 'hideBubble':
        bubbleOn = false;
        return null;
      case 'getMetricsSummary':
        return summary;
      case 'checkBackend':
        return check;
      case 'exportMetrics':
        return '/data/exports/metrics-1.jsonl';
      default:
        return null;
    }
  }
}

late FakeCore core;

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const control = MethodChannel('pointly/control');
  const events = EventChannel('pointly/events');

  setUp(() {
    core = FakeCore();
    PointlyChannel.resetForTest();
    Lang.set('en');
    final messenger = TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    messenger.setMockMethodCallHandler(control, core.handle);
    messenger.setMockStreamHandler(
      events,
      MockStreamHandler.inline(onListen: (args, sink) {
        sink.success({'state': 'IDLE'});
        core.events.stream.listen(sink.success);
      }),
    );
  });

  Future<void> pump(WidgetTester tester, Widget home) async {
    tester.view.physicalSize = const Size(1280, 2856);
    tester.view.devicePixelRatio = 3.0;
    addTearDown(tester.view.reset);
    await tester.pumpWidget(MaterialApp(
      builder: (context, child) => LangScope(child: child!),
      home: home,
      routes: {
        Routes.onboarding: (_) => const OnboardingScreen(),
        Routes.permissions: (_) => const PermissionsScreen(),
        Routes.home: (_) => const HomeScreen(),
        Routes.settings: (_) => const SettingsScreen(),
        Routes.metrics: (_) => const MetricsScreen(),
      },
    ));
    await tester.pumpAndSettle();
  }

  Future<void> tapText(WidgetTester tester, String text, {int index = 0}) async {
    final f = find.text(text).at(index);
    await tester.scrollUntilVisible(find.text(text).first, 200, scrollable: find.byType(Scrollable).last);
    await tester.ensureVisible(f);
    await tester.pumpAndSettle();
    await tester.tap(f);
    await tester.pumpAndSettle();
  }

  group('start-up', () {
    testWidgets('first run goes to onboarding, later runs to Home', (tester) async {
      tester.view.physicalSize = const Size(1280, 2856);
      tester.view.devicePixelRatio = 3.0;
      addTearDown(tester.view.reset);
      await tester.pumpWidget(const PointlyApp());
      await tester.pumpAndSettle();
      expect(find.byType(OnboardingScreen), findsOneWidget);

      core.config['onboarded'] = true;
      core.mic = true;
      core.notifications = true;
      await tester.pumpWidget(const SizedBox());
      await tester.pumpWidget(const PointlyApp());
      await tester.pumpAndSettle();
      expect(find.byType(HomeScreen), findsOneWidget);
    });

    testWidgets('missing permissions after onboarding go to the checklist', (tester) async {
      core.config['onboarded'] = true;
      tester.view.physicalSize = const Size(1280, 2856);
      tester.view.devicePixelRatio = 3.0;
      addTearDown(tester.view.reset);
      await tester.pumpWidget(const PointlyApp());
      await tester.pumpAndSettle();
      expect(find.byType(PermissionsScreen), findsOneWidget);
    });
  });

  group('onboarding', () {
    testWidgets('choosing Urdu on the first page switches the app and saves it', (tester) async {
      await pump(tester, const OnboardingScreen());
      await tester.tap(find.text(S.setUrdu.ur));
      await tester.pumpAndSettle();
      expect(core.config['language'], 'ur');
      expect(find.text(S.onb1Title.ur), findsOneWidget);
      expect(find.text(S.onb1Title.en), findsNothing);
      await tester.tap(find.text(S.setEnglish.en));
      await tester.pumpAndSettle();
      expect(find.text(S.onb1Title.en), findsOneWidget);
    });

    testWidgets('three pages with the three promises, then permissions', (tester) async {
      await pump(tester, const OnboardingScreen());
      expect(find.text(S.onb1Title.en), findsOneWidget);
      await tester.tap(find.text(S.next.en));
      await tester.pumpAndSettle();
      expect(find.text(S.promise1.en), findsOneWidget);
      expect(find.text(S.promise3.en), findsOneWidget);
      await tester.tap(find.text(S.next.en));
      await tester.pumpAndSettle();
      await tester.tap(find.text(S.start.en));
      await tester.pumpAndSettle();
      expect(core.config['onboarded'], true);
      expect(find.byType(PermissionsScreen), findsOneWidget);
    });
  });

  group('permissions', () {
    testWidgets('buttons ask for each permission; continue needs the service', (tester) async {
      core.serviceOn = false;
      await pump(tester, const PermissionsScreen());
      final continueButton = find.widgetWithText(FilledButton, S.permContinue.en);
      expect(tester.widget<FilledButton>(continueButton).onPressed, isNull);

      await tester.tap(find.text(S.permOpen.en));
      await tester.pumpAndSettle();
      expect(core.methods, contains('openAccessibilitySettings'));

      await tester.tap(find.text(S.permGrant.en).first);
      await tester.pumpAndSettle();
      expect(core.last('requestPermission').arguments, {'name': 'microphone'});

      core.serviceOn = true;
      core.mic = true;
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.paused);
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
      await tester.pumpAndSettle();
      expect(find.text(S.permDone.en), findsNWidgets(2));
      expect(tester.widget<FilledButton>(continueButton).onPressed, isNotNull);
    });
  });

  group('home', () {
    testWidgets('start/stop button shows and hides the bubble', (tester) async {
      await pump(tester, const HomeScreen());
      await tester.tap(find.text(S.homeStart.en));
      await tester.pumpAndSettle();
      expect(core.methods, contains('showBubble'));
      expect(find.text(S.homeStop.en), findsOneWidget);
      await tester.tap(find.text(S.homeStop.en));
      await tester.pumpAndSettle();
      expect(core.methods, contains('hideBubble'));
    });

    testWidgets('each quick task sends its English goal', (tester) async {
      await pump(tester, const HomeScreen());
      for (final task in S.quickTasks) {
        await tapText(tester, task.en);
        expect(core.last('startQuickTask').arguments, {'goal': task.en});
      }
      expect(core.calls.where((c) => c.method == 'startQuickTask').length, 5);
    });

    testWidgets('quick task with the service off goes to permissions', (tester) async {
      core.serviceOn = false;
      await pump(tester, const HomeScreen());
      await tapText(tester, S.quickTasks.first.en);
      expect(core.methods, isNot(contains('startQuickTask')));
      expect(find.byType(PermissionsScreen), findsOneWidget);
    });

    testWidgets('session events update the status card', (tester) async {
      final states = StreamController<PointlyState>.broadcast();
      addTearDown(states.close);
      await pump(tester, HomeScreen(states: states.stream));
      expect(find.text('${S.homeState.en}: Ready'), findsOneWidget);

      states.add(PointlyState.fromMap({
        'state': 'WAITING_FOR_USER',
        'goalText': 'Turn on WiFi',
        'step': 2,
        'caption': 'Tap Network & internet',
      }));
      await tester.pumpAndSettle();
      expect(find.text('${S.homeState.en}: Waiting for you'), findsOneWidget);
      expect(find.text('“Turn on WiFi”'), findsOneWidget);
      expect(find.text('Tap Network & internet'), findsOneWidget);

      states.add(PointlyState.fromMap({'state': 'ERROR', 'error': 'NETWORK: timeout'}));
      await tester.pumpAndSettle();
      expect(find.text('${S.homeState.en}: Something went wrong'), findsOneWidget);
      expect(find.text('NETWORK: timeout'), findsOneWidget);
    });
  });

  group('settings', () {
    testWidgets('choices are saved straight away', (tester) async {
      await pump(tester, const SettingsScreen());
      await tapText(tester, S.setCloud.en);
      expect(core.config['voiceIn'], 'cloud');
      await tapText(tester, S.setTap.en);
      expect(core.config['talkMode'], 'tap');
      await tapText(tester, S.setDemoMode.en);
      expect(core.config['demoMode'], true);
      await tapText(tester, S.setDebugBoxes.en);
      expect(core.last('setDebugBoxes').arguments, {'enabled': true});
      await tapText(tester, S.setResetLearning.en);
      expect(core.methods, contains('resetLearning'));
    });

    testWidgets('language choice switches the whole screen to Urdu', (tester) async {
      await pump(tester, const SettingsScreen());
      expect(find.text(S.setTitle.en), findsOneWidget);
      await tapText(tester, S.setUrdu.ur);
      expect(core.config['language'], 'ur');
      expect(find.text(S.setTitle.ur), findsWidgets);
      expect(find.text(S.setVoiceIn.ur), findsOneWidget);
      expect(find.text(S.setVoiceIn.en), findsNothing);
    });

    testWidgets('check server saves URL and key first, then reports', (tester) async {
      await pump(tester, const SettingsScreen());
      await tester.enterText(find.byType(TextField).first, 'http://192.168.1.10:8000');
      await tester.enterText(find.byType(TextField).at(1), 'demo-key');
      await tapText(tester, S.setCheck.en);
      expect(core.config['baseUrl'], 'http://192.168.1.10:8000');
      expect(core.config['appKey'], 'demo-key');
      expect(core.methods.indexOf('setConfig'), lessThan(core.methods.indexOf('checkBackend')));
      expect(find.textContaining(S.setCheckOk.en), findsOneWidget);
      expect(find.textContaining('gemini-3.8-flash'), findsOneWidget);

      core.check = {'reachable': true, 'model': 'mock', 'keyOk': false};
      await tapText(tester, S.setCheck.en);
      expect(find.textContaining(S.setCheckBadKey.en), findsOneWidget);

      core.check = {'reachable': false, 'error': 'Connection refused'};
      await tapText(tester, S.setCheck.en);
      expect(find.textContaining(S.setCheckDown.en), findsOneWidget);
      expect(find.textContaining('Connection refused'), findsOneWidget);
    });
  });

  group('metrics', () {
    testWidgets('summary numbers, export and snapshot', (tester) async {
      core.summary = {
        'sessions': 8,
        'steps': 22,
        'successRate': 0.625,
        'medianPlannerMs': 683,
        'medianStepMs': 3612,
        'medianReadMs': 32,
        'medianSettleToPointerMs': 706,
        'cacheHitRate': 0.24,
        'medianCachedMs': 0,
        'sensitivePauses': 1,
      };
      await pump(tester, const MetricsScreen());
      for (final v in ['8', '22', '63%', '683', '3612', '32', '706', '24%', '1']) {
        expect(find.text(v), findsWidgets, reason: v);
      }
      await tapText(tester, S.metExport.en);
      expect(core.methods, contains('exportMetrics'));
      await tapText(tester, S.metSaveSnapshot.en);
      expect(core.last('saveSnapshot').arguments, {'delayMs': 5000});
    });

    testWidgets('empty log shows dashes, not zeros', (tester) async {
      await pump(tester, const MetricsScreen());
      expect(find.text('—'), findsNWidgets(10));
    });
  });

  test('channel wrapper maps a missing service to SERVICE_OFF', () async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(
      control,
      (call) async => throw PlatformException(code: 'SERVICE_OFF', message: 'off'),
    );
    expect(
      PointlyChannel.showBubble(),
      throwsA(isA<PlatformException>().having((e) => e.code, 'code', 'SERVICE_OFF')),
    );
  });
}
