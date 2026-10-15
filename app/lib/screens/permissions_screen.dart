import 'package:flutter/material.dart';

import '../channel/pointly_channel.dart';
import '../l10n/strings.dart';
import '../main.dart';
import '../theme/pointly_theme.dart';
import '../widgets/bilingual.dart';
import '../widgets/ui.dart';

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
    final t = Theme.of(context).textTheme;
    final canPop = Navigator.of(context).canPop();
    final next = !_status.accessibility
        ? 1
        : !_status.microphone
        ? 2
        : !_status.notifications
        ? 3
        : 0;
    return Scaffold(
      appBar: canPop ? AppBar() : null,
      body: SafeArea(
        top: !canPop,
        child: ListView(
          padding: EdgeInsets.fromLTRB(20, canPop ? 0 : 24, 20, 24),
          children: [
            Reveal(
              index: 0,
              child: BiText(S.permHeader, size: t.displaySmall!.fontSize!, color: Palette.ink),
            ),
            const SizedBox(height: 8),
            Reveal(index: 1, child: BiText(S.permSub, size: 17, bold: false, color: Palette.inkSoft)),
            const SizedBox(height: 24),
            Reveal(
              index: 2,
              child: _Step(
                number: 1,
                title: S.permAccessibility,
                body: S.permAccessibilityBody,
                granted: _status.accessibility,
                action: S.permOpen,
                onPressed: PointlyChannel.openAccessibilitySettings,
                help: S.permAccessibilityHelp,
                emphasize: next == 1,
              ),
            ),
            Reveal(
              index: 3,
              child: _Step(
                number: 2,
                title: S.permMicrophone,
                body: S.permMicrophoneBody,
                granted: _status.microphone,
                action: S.permGrant,
                onPressed: () => PointlyChannel.requestPermission('microphone'),
                emphasize: next == 2,
              ),
            ),
            Reveal(
              index: 4,
              child: _Step(
                number: 3,
                title: S.permNotifications,
                body: S.permNotificationsBody,
                granted: _status.notifications,
                action: S.permGrant,
                onPressed: () => PointlyChannel.requestPermission('notifications'),
                emphasize: next == 3,
                last: true,
              ),
            ),
          ],
        ),
      ),
      bottomNavigationBar: SafeArea(
        child: Padding(
          padding: const EdgeInsets.fromLTRB(20, 8, 20, 16),
          child: BigButton(
            label: S.permContinue,
            icon: Icons.arrow_forward_rounded,
            onPressed: _status.accessibility
                ? () => Navigator.of(context).pushNamedAndRemoveUntil(Routes.home, (_) => false)
                : null,
          ),
        ),
      ),
    );
  }
}

class _Step extends StatelessWidget {
  const _Step({
    required this.number,
    required this.title,
    required this.body,
    required this.granted,
    required this.action,
    required this.onPressed,
    this.help,
    this.emphasize = false,
    this.last = false,
  });

  final int number;
  final Bi title;
  final Bi body;
  final bool granted;
  final Bi action;
  final VoidCallback onPressed;
  final Bi? help;
  final bool emphasize;
  final bool last;

  @override
  Widget build(BuildContext context) {
    return IntrinsicHeight(
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Column(
            children: [
              StepBadge(number: number, done: granted),
              if (!last)
                Expanded(
                  child: AnimatedContainer(
                    duration: Motion.of(context, Motion.base),
                    width: 2,
                    margin: const EdgeInsets.symmetric(vertical: 6),
                    color: granted ? Palette.brand : Palette.line,
                  ),
                ),
            ],
          ),
          const SizedBox(width: 14),
          Expanded(
            child: Padding(
              padding: EdgeInsets.only(bottom: last ? 0 : 22),
              child: Card(
                child: Padding(
                  padding: const EdgeInsets.all(16),
                  child: SmoothSize(
                    alignment: AlignmentDirectional.topStart,
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        BiText(title, size: 19, color: Palette.ink),
                        const SizedBox(height: 4),
                        BiText(body, size: 16, bold: false, color: Palette.inkSoft),
                        const SizedBox(height: 14),
                        if (granted)
                          Row(
                            children: [
                              const Icon(Icons.check_circle_rounded, color: Palette.brand, size: 22),
                              const SizedBox(width: 8),
                              BiText(S.permDone, size: 17, color: Palette.brand),
                            ],
                          )
                        else ...[
                          SizedBox(
                            width: double.infinity,
                            child: BigButton(
                              label: action,
                              tone: emphasize ? ButtonTone.primary : ButtonTone.secondary,
                              minHeight: 54,
                              onPressed: onPressed,
                            ),
                          ),
                          if (help != null) ...[
                            const SizedBox(height: 10),
                            Row(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                const Padding(
                                  padding: EdgeInsets.only(top: 2),
                                  child: Icon(Icons.info_outline_rounded, size: 18, color: Palette.inkFaint),
                                ),
                                const SizedBox(width: 8),
                                Expanded(child: BiText(help!, size: 14, bold: false, color: Palette.inkSoft)),
                              ],
                            ),
                          ],
                        ],
                      ],
                    ),
                  ),
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }
}
