import 'package:flutter/material.dart';

import '../channel/rehnuma_channel.dart';
import '../l10n/strings.dart';
import '../widgets/bilingual.dart';

/// Session summary, export and the debug snapshot button (SPEC 10 item 5, SPEC 11).
class MetricsScreen extends StatefulWidget {
  const MetricsScreen({super.key});

  @override
  State<MetricsScreen> createState() => _MetricsScreenState();
}

class _MetricsScreenState extends State<MetricsScreen> {
  Map<String, dynamic> _summary = {};

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final s = await RehnumaChannel.getMetricsSummary();
    if (mounted) setState(() => _summary = s);
  }

  String _fmt(String key, {bool percent = false}) {
    final v = _summary[key];
    if (v == null) return '—';
    if (v is num) return percent ? '${(v * 100).round()}%' : v.round().toString();
    return v.toString();
  }

  Future<void> _snapshot() async {
    try {
      await RehnumaChannel.saveSnapshot();
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('${S.metSnapshotHint.ur}\n${S.metSnapshotHint.en}')),
      );
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('$e')));
    }
  }

  @override
  Widget build(BuildContext context) {
    final rows = <(Bi, String)>[
      (S.metSessions, _fmt('sessions')),
      (S.metSteps, _fmt('steps')),
      (S.metSuccessRate, _fmt('successRate', percent: true)),
      (S.metPlannerMs, _fmt('medianPlannerMs')),
      (S.metStepMs, _fmt('medianStepMs')),
      (S.metReadMs, _fmt('medianReadMs')),
      (S.metSettleToPointer, _fmt('medianSettleToPointerMs')),
      (S.metCacheRate, _fmt('cacheHitRate', percent: true)),
      (S.metCachedMs, _fmt('medianCachedMs')),
      (S.metSensitive, _fmt('sensitivePauses')),
    ];
    return Scaffold(
      appBar: AppBar(
        title: Text('${S.metTitle.ur} · ${S.metTitle.en}'),
        actions: [
          IconButton(icon: const Icon(Icons.refresh_rounded), tooltip: S.metRefresh.en, onPressed: _load),
        ],
      ),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          Card(
            child: Column(
              children: [
                for (final (label, value) in rows)
                  ListTile(
                    title: BiText(label, size: 18),
                    trailing: Text(value, style: const TextStyle(fontSize: 22, fontWeight: FontWeight.bold)),
                  ),
              ],
            ),
          ),
          const SizedBox(height: 16),
          BigButton(
            label: S.metExport,
            icon: Icons.ios_share_rounded,
            onPressed: () => RehnumaChannel.exportMetrics(),
          ),
          const SizedBox(height: 24),
          BiText(S.metDebug, size: 20),
          const SizedBox(height: 8),
          BigButton(
            label: S.metSaveSnapshot,
            icon: Icons.save_alt_rounded,
            color: Colors.white,
            foreground: const Color(0xFF111111),
            onPressed: _snapshot,
          ),
          const SizedBox(height: 8),
          BiText(S.metSnapshotHint, size: 16),
        ],
      ),
    );
  }
}
