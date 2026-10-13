import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../channel/rehnuma_channel.dart';
import '../l10n/strings.dart';
import '../main.dart';
import '../widgets/bilingual.dart';

/// Start/stop button, the five quick tasks and the live session state (SPEC 10 item 3).
class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key});

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> with WidgetsBindingObserver {
  StreamSubscription<RehnumaState>? _sub;
  RehnumaState _state = RehnumaState.idle;
  bool _bubbleOn = false;
  bool _serviceOn = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _sub = RehnumaChannel.states.listen((s) => setState(() => _state = s));
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
    final service = await RehnumaChannel.isServiceEnabled();
    final bubble = await RehnumaChannel.isBubbleVisible();
    if (mounted) {
      setState(() {
        _serviceOn = service;
        _bubbleOn = bubble;
      });
    }
  }

  Future<bool> _ensureService() async {
    if (await RehnumaChannel.isServiceEnabled()) return true;
    if (!mounted) return false;
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text('${S.homeServiceOff.ur}\n${S.homeServiceOff.en}')),
    );
    Navigator.of(context).pushNamed(Routes.permissions);
    return false;
  }

  Future<void> _toggle() async {
    if (!await _ensureService()) return;
    try {
      if (_bubbleOn) {
        await RehnumaChannel.hideBubble();
      } else {
        await RehnumaChannel.showBubble();
      }
    } on PlatformException catch (e) {
      _showError(e);
    }
    await _refresh();
  }

  Future<void> _quickTask(QuickTask task) async {
    if (!await _ensureService()) return;
    try {
      await RehnumaChannel.startQuickTask(task.goal);
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
    final scheme = Theme.of(context).colorScheme;
    final stateLabel = S.stateLabel(_state.state);
    return Scaffold(
      appBar: AppBar(
        title: Text('${S.appName.ur} · ${S.appName.en}'),
        actions: [
          IconButton(
            tooltip: S.homeMetrics.en,
            iconSize: 30,
            icon: const Icon(Icons.insights_rounded),
            onPressed: () => Navigator.of(context).pushNamed(Routes.metrics),
          ),
          IconButton(
            tooltip: S.homeSettings.en,
            iconSize: 30,
            icon: const Icon(Icons.settings_rounded),
            onPressed: () => Navigator.of(context).pushNamed(Routes.settings),
          ),
        ],
      ),
      body: RefreshIndicator(
        onRefresh: _refresh,
        child: ListView(
          padding: const EdgeInsets.all(16),
          children: [
            BigButton(
              label: _bubbleOn ? S.homeStop : S.homeStart,
              icon: _bubbleOn ? Icons.stop_circle_rounded : Icons.play_circle_fill_rounded,
              color: _bubbleOn ? const Color(0xFFB3261E) : scheme.primary,
              minHeight: 96,
              onPressed: _toggle,
            ),
            const SizedBox(height: 12),
            Card(
              child: Padding(
                padding: const EdgeInsets.all(16),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    BiText(Bi('${S.homeState.ur}: ${stateLabel.ur}', '${S.homeState.en}: ${stateLabel.en}')),
                    if (_state.goalText != null) ...[
                      const SizedBox(height: 8),
                      Text('“${_state.goalText}”', style: const TextStyle(fontSize: 18)),
                    ],
                    if (_state.captionRoman != null) ...[
                      const SizedBox(height: 8),
                      Text(_state.captionRoman!, style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w600)),
                    ],
                    if (_state.error != null) ...[
                      const SizedBox(height: 8),
                      Text(_state.error!, style: TextStyle(fontSize: 16, color: scheme.error)),
                    ],
                    if (!_serviceOn) ...[
                      const SizedBox(height: 8),
                      TextButton(
                        onPressed: () => Navigator.of(context).pushNamed(Routes.permissions),
                        child: BiText(S.homeServiceOff, size: 16, color: scheme.error),
                      ),
                    ],
                  ],
                ),
              ),
            ),
            const SizedBox(height: 20),
            BiText(S.homeQuickTasks, size: 22),
            const SizedBox(height: 8),
            for (final task in S.quickTasks)
              Padding(
                padding: const EdgeInsets.symmetric(vertical: 6),
                child: BigButton(
                  label: Bi(task.ur, '${task.goal} · ${task.en}'),
                  icon: Icons.play_arrow_rounded,
                  color: Colors.white,
                  foreground: const Color(0xFF111111),
                  minHeight: 64,
                  onPressed: () => _quickTask(task),
                ),
              ),
          ],
        ),
      ),
    );
  }
}
