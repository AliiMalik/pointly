import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import 'channel/pointly_channel.dart';
import 'l10n/strings.dart';
import 'screens/home_screen.dart';
import 'screens/metrics_screen.dart';
import 'screens/onboarding_screen.dart';
import 'screens/permissions_screen.dart';
import 'screens/settings_screen.dart';
import 'theme/pointly_theme.dart';
import 'widgets/marks.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  SystemChrome.setSystemUIOverlayStyle(
    const SystemUiOverlayStyle(
      statusBarColor: Colors.transparent,
      statusBarIconBrightness: Brightness.dark,
      systemNavigationBarColor: Palette.paper,
      systemNavigationBarIconBrightness: Brightness.dark,
    ),
  );
  runApp(const PointlyApp());
}

class Routes {
  static const onboarding = '/onboarding';
  static const permissions = '/permissions';
  static const home = '/home';
  static const settings = '/settings';
  static const metrics = '/metrics';
}

class PointlyApp extends StatelessWidget {
  const PointlyApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: S.appName,
      debugShowCheckedModeBanner: false,
      theme: PointlyTheme.light(),
      builder: (context, child) => ValueListenableBuilder<String>(
        valueListenable: Lang.code,
        builder: (context, _, _) => LangScope(
          child: Directionality(textDirection: Lang.direction, child: child!),
        ),
      ),
      home: const _StartGate(),
      routes: {
        Routes.onboarding: (_) => const OnboardingScreen(),
        Routes.permissions: (_) => const PermissionsScreen(),
        Routes.home: (_) => const HomeScreen(),
        Routes.settings: (_) => const SettingsScreen(),
        Routes.metrics: (_) => const MetricsScreen(),
      },
    );
  }
}

class _StartGate extends StatefulWidget {
  const _StartGate();

  @override
  State<_StartGate> createState() => _StartGateState();
}

class _StartGateState extends State<_StartGate> {
  @override
  void initState() {
    super.initState();
    _route();
  }

  Future<void> _route() async {
    var target = Routes.home;
    try {
      final config = await PointlyChannel.getConfig();
      Lang.set(config['language'] as String?);
      if (config['onboarded'] != true) {
        target = Routes.onboarding;
      } else if (!(await PointlyChannel.getPermissions()).allGranted) {
        target = Routes.permissions;
      }
    } catch (_) {
      target = Routes.onboarding;
    }
    if (!mounted) return;
    Navigator.of(context).pushReplacementNamed(target);
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    body: Center(
      child: TweenAnimationBuilder<double>(
        tween: Tween(begin: 0.85, end: 1),
        duration: Motion.of(context, Motion.slow),
        curve: Motion.spring,
        builder: (context, v, child) => Transform.scale(scale: v, child: child),
        child: const PointlyMark(size: 88),
      ),
    ),
  );
}
