import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../channel/pointly_channel.dart';
import '../l10n/strings.dart';
import '../main.dart';
import '../theme/pointly_theme.dart';
import '../widgets/bilingual.dart';
import '../widgets/guide_demo.dart';
import '../widgets/marks.dart';
import '../widgets/ui.dart';

class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key, this.states});

  final Stream<PointlyState>? states;

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> with WidgetsBindingObserver {
  static const _taskIcons = [
    Icons.image_rounded,
    Icons.format_size_rounded,
    Icons.wifi_rounded,
    Icons.call_rounded,
    Icons.account_balance_wallet_rounded,
  ];

  StreamSubscription<PointlyState>? _sub;
  PointlyState _state = PointlyState.idle;
  bool _bubbleOn = false;
  bool _serviceOn = true;
  bool _toggling = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _sub = (widget.states ?? PointlyChannel.states).listen((s) => setState(() => _state = s));
    _refresh();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _sub?.cancel();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) _refresh();
  }

  Future<void> _refresh() async {
    final service = await PointlyChannel.isServiceEnabled();
    final bubble = await PointlyChannel.isBubbleVisible();
    if (mounted) {
      setState(() {
        _serviceOn = service;
        _bubbleOn = bubble;
      });
    }
  }

  Future<bool> _ensureService() async {
    if (await PointlyChannel.isServiceEnabled()) return true;
    if (!mounted) return false;
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(S.homeServiceOff.text)));
    Navigator.of(context).pushNamed(Routes.permissions);
    return false;
  }

  Future<void> _toggle() async {
    if (_toggling) return;
    setState(() => _toggling = true);
    try {
      if (!await _ensureService()) return;
      HapticFeedback.mediumImpact();
      if (_bubbleOn) {
        await PointlyChannel.hideBubble();
      } else {
        await PointlyChannel.showBubble();
      }
      await _refresh();
    } on PlatformException catch (e) {
      _showError(e);
    } finally {
      if (mounted) setState(() => _toggling = false);
    }
  }

  Future<void> _quickTask(Bi task) async {
    if (!await _ensureService()) return;
    try {
      await PointlyChannel.startQuickTask(task.en);
    } on PlatformException catch (e) {
      _showError(e);
    }
    await _refresh();
  }

  void _showError(PlatformException e) {
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(e.message ?? e.code)));
  }

  @override
  Widget build(BuildContext context) {
    Lang.watch(context);
    return Scaffold(
      body: SafeArea(
        child: RefreshIndicator(
          onRefresh: _refresh,
          child: ListView(
            padding: const EdgeInsets.fromLTRB(20, 8, 20, 32),
            children: [
              _Header(onSettings: () => Navigator.of(context).pushNamed(Routes.settings)),
              const SizedBox(height: 12),
              if (!_serviceOn) ...[
                Reveal(
                  index: 0,
                  child: InfoBanner(
                    icon: Icons.accessibility_new_rounded,
                    text: S.homeServiceOff,
                    action: S.homeSetUp,
                    onTap: () => Navigator.of(context).pushNamed(Routes.permissions),
                  ),
                ),
                const SizedBox(height: 16),
              ],
              Reveal(
                index: 0,
                child: _BubbleHero(on: _bubbleOn, busy: _toggling, onToggle: _toggle),
              ),
              const SizedBox(height: 28),
              if (_bubbleOn || _state.state != 'IDLE') ...[
                Reveal(index: 1, child: SectionLabel(S.homeNow)),
                Reveal(index: 1, child: _StatusCard(state: _state)),
                const SizedBox(height: 28),
              ],
              Reveal(index: 2, child: SectionLabel(S.homeQuickTasks)),
              for (var i = 0; i < S.quickTasks.length; i++)
                Padding(
                  padding: const EdgeInsets.only(bottom: 10),
                  child: Reveal(
                    index: 3 + i,
                    child: ActionTile(
                      icon: _taskIcons[i % _taskIcons.length],
                      title: S.quickTasks[i],
                      onTap: () => _quickTask(S.quickTasks[i]),
                    ),
                  ),
                ),
            ],
          ),
        ),
      ),
    );
  }
}

class _Header extends StatelessWidget {
  const _Header({required this.onSettings});

  final VoidCallback onSettings;

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        const PointlyMark(size: 32),
        const SizedBox(width: 10),
        const Text(
          S.appName,
          style: TextStyle(fontSize: 22, fontWeight: FontWeight.w800, letterSpacing: -0.6, color: Palette.ink),
        ),
        const Spacer(),
        TextButton.icon(
          onPressed: onSettings,
          icon: const Icon(Icons.tune_rounded),
          label: Text(S.homeSettings.text),
          style: TextButton.styleFrom(foregroundColor: Palette.ink, minimumSize: const Size(48, 48)),
        ),
      ],
    );
  }
}

class _BubbleHero extends StatelessWidget {
  const _BubbleHero({required this.on, required this.busy, required this.onToggle});

  final bool on;
  final bool busy;
  final VoidCallback onToggle;

  @override
  Widget build(BuildContext context) {
    final text = Theme.of(context).textTheme;
    return Card(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(22, 18, 22, 22),
        child: Column(
          children: [
            SizedBox(
              height: 176,
              child: Stack(
                alignment: Alignment.center,
                children: [
                  BubbleHalo(active: on, size: 176),
                  Semantics(
                    container: true,
                    button: !on,
                    label: on ? null : S.homeStart.text,
                    onTap: on ? null : onToggle,
                    excludeSemantics: true,
                    child: GestureDetector(
                      onTap: on ? null : onToggle,
                      child: AnimatedScale(
                        scale: on ? 1 : 0.92,
                        duration: Motion.of(context, Motion.slow),
                        curve: Motion.spring,
                        child: AnimatedSwitcher(
                          duration: Motion.of(context, Motion.base),
                          switchInCurve: Motion.enter,
                          transitionBuilder: (child, a) => FadeTransition(
                            opacity: a,
                            child: ScaleTransition(scale: Tween(begin: 0.9, end: 1.0).animate(a), child: child),
                          ),
                          child: Container(
                            key: ValueKey(on),
                            decoration: BoxDecoration(
                              shape: BoxShape.circle,
                              boxShadow: [
                                BoxShadow(
                                  color: (on ? Palette.brand : Palette.ink).withValues(alpha: on ? 0.28 : 0.10),
                                  blurRadius: on ? 28 : 16,
                                  offset: const Offset(0, 10),
                                ),
                              ],
                            ),
                            child: PointlyMark(size: 104, muted: !on),
                          ),
                        ),
                      ),
                    ),
                  ),
                ],
              ),
            ),
            const SizedBox(height: 6),
            AnimatedSwitcher(
              duration: Motion.of(context, Motion.base),
              child: Column(
                key: ValueKey(on),
                children: [
                  BiText(
                    on ? S.homeOnTitle : S.homeOffTitle,
                    size: text.titleLarge!.fontSize!,
                    center: true,
                    color: Palette.ink,
                  ),
                  const SizedBox(height: 6),
                  BiText(
                    on ? S.homeOnBody : S.homeOffBody,
                    size: 16,
                    bold: false,
                    center: true,
                    color: Palette.inkSoft,
                  ),
                ],
              ),
            ),
            const SizedBox(height: 20),
            SizedBox(
              width: double.infinity,
              child: BigButton(
                label: on ? S.homeStop : S.homeStart,
                icon: on ? Icons.stop_rounded : Icons.play_arrow_rounded,
                tone: on ? ButtonTone.secondary : ButtonTone.primary,
                busy: busy,
                onPressed: onToggle,
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _StatusCard extends StatelessWidget {
  const _StatusCard({required this.state});

  final PointlyState state;

  @override
  Widget build(BuildContext context) {
    final idle = state.goalText == null && state.caption == null && state.error == null;
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(18),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            StatusPill(state: state.state),
            SmoothSize(
              alignment: AlignmentDirectional.topStart,
              child: AnimatedSwitcher(
                duration: Motion.of(context, Motion.base),
                layoutBuilder: (current, previous) =>
                    Stack(alignment: AlignmentDirectional.topStart, children: [...previous, ?current]),
                child: Column(
                  key: ValueKey('${state.state}|${state.goalText}|${state.caption}|${state.error}'),
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    if (idle) ...[
                      const SizedBox(height: 14),
                      BiText(S.homeIdleHint, size: 17, bold: false, color: Palette.inkSoft),
                    ],
                    if (state.goalText != null) ...[
                      const SizedBox(height: 14),
                      Text(
                        '“${state.goalText}”',
                        style: const TextStyle(
                          fontSize: 20,
                          fontWeight: FontWeight.w700,
                          color: Palette.ink,
                          height: 1.35,
                        ),
                      ),
                    ],
                    if (state.caption != null) ...[
                      const SizedBox(height: 10),
                      Container(
                        padding: const EdgeInsetsDirectional.only(start: 12),
                        decoration: const BoxDecoration(
                          border: BorderDirectional(start: BorderSide(color: Palette.signal, width: 3)),
                        ),
                        child: Text(
                          state.caption!,
                          textDirection: Lang.direction,
                          style: TextStyle(fontSize: 18, color: Palette.ink, height: Lang.isUrdu ? 1.7 : 1.4),
                        ),
                      ),
                    ],
                    if (state.error != null) ...[
                      const SizedBox(height: 10),
                      Row(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          const Padding(
                            padding: EdgeInsets.only(top: 2),
                            child: Icon(Icons.error_outline_rounded, color: Palette.danger, size: 20),
                          ),
                          const SizedBox(width: 8),
                          Expanded(
                            child: Text(state.error!, style: const TextStyle(fontSize: 16, color: Palette.danger)),
                          ),
                        ],
                      ),
                    ],
                  ],
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
