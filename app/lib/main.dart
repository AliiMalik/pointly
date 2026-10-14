import 'package:flutter/material.dart';

import 'channel/pointly_channel.dart';
import 'l10n/strings.dart';
import 'screens/home_screen.dart';
import 'screens/metrics_screen.dart';
import 'screens/onboarding_screen.dart';
import 'screens/permissions_screen.dart';
import 'screens/settings_screen.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
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

  static const green = Color(0xFF0B7A3E);

  @override
  Widget build(BuildContext context) {
    final scheme = ColorScheme.fromSeed(
      seedColor: green,
      primary: green,
      onPrimary: Colors.white,
      surface: Colors.white,
      onSurface: const Color(0xFF111111),
    );
    return MaterialApp(
      title: S.appName,
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        colorScheme: scheme,
        useMaterial3: true,
        scaffoldBackgroundColor: const Color(0xFFF6F7F4),
        appBarTheme: const AppBarTheme(
          backgroundColor: green,
          foregroundColor: Colors.white,
          titleTextStyle: TextStyle(fontSize: 22, fontWeight: FontWeight.w600, color: Colors.white),
        ),
        listTileTheme: const ListTileThemeData(minVerticalPadding: 14),
      ),
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
  Widget build(BuildContext context) =>
      const Scaffold(body: Center(child: CircularProgressIndicator()));
}
