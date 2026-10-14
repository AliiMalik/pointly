import 'package:flutter/material.dart';

import '../channel/pointly_channel.dart';
import '../l10n/strings.dart';
import '../main.dart';
import '../widgets/bilingual.dart';

class PermissionsScreen extends StatefulWidget {
  const PermissionsScreen({super.key});

  @override
  State<PermissionsScreen> createState() => _PermissionsScreenState();
}

class _PermissionsScreenState extends State<PermissionsScreen> with WidgetsBindingObserver {
  PermissionStatus _status = PermissionStatus.none;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _refresh();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) _refresh();
  }

  Future<void> _refresh() async {
    final s = await PointlyChannel.getPermissions();
    if (mounted) setState(() => _status = s);
  }

  @override
  Widget build(BuildContext context) {
    Lang.watch(context);
    return Scaffold(
      appBar: AppBar(title: Text(S.permTitle.text)),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          _PermissionRow(
            label: S.permAccessibility,
            granted: _status.accessibility,
            action: S.permOpen,
            onPressed: PointlyChannel.openAccessibilitySettings,
            help: S.permAccessibilityHelp,
          ),
          _PermissionRow(
            label: S.permMicrophone,
            granted: _status.microphone,
            action: S.permGrant,
            onPressed: () => PointlyChannel.requestPermission('microphone'),
          ),
          _PermissionRow(
            label: S.permNotifications,
            granted: _status.notifications,
            action: S.permGrant,
            onPressed: () => PointlyChannel.requestPermission('notifications'),
          ),
          const SizedBox(height: 24),
          BigButton(
            label: S.permContinue,
            icon: Icons.arrow_forward_rounded,
            onPressed: _status.accessibility
                ? () => Navigator.of(context).pushNamedAndRemoveUntil(Routes.home, (_) => false)
                : null,
          ),
        ],
      ),
    );
  }
}

class _PermissionRow extends StatelessWidget {
  const _PermissionRow({
    required this.label,
    required this.granted,
    required this.action,
    required this.onPressed,
    this.help,
  });

  final Bi label;
  final bool granted;
  final Bi action;
  final VoidCallback onPressed;
  final Bi? help;

  @override
  Widget build(BuildContext context) {
    final green = Theme.of(context).colorScheme.primary;
    return Card(
      margin: const EdgeInsets.symmetric(vertical: 8),
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Row(
              children: [
                Icon(
                  granted ? Icons.check_circle_rounded : Icons.radio_button_unchecked_rounded,
                  color: granted ? green : Colors.black45,
                  size: 36,
                ),
                const SizedBox(width: 12),
                Expanded(child: BiText(label, size: 20)),
              ],
            ),
            const SizedBox(height: 12),
            if (!granted)
              BigButton(label: action, onPressed: onPressed, minHeight: 56)
            else
              BiText(S.permDone, size: 18, color: green),
            if (help != null && !granted) ...[
              const SizedBox(height: 8),
              BiText(help!, size: 16),
            ],
          ],
        ),
      ),
    );
  }
}
