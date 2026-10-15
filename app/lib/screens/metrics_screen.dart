import 'package:flutter/material.dart';

import '../channel/pointly_channel.dart';
import '../l10n/strings.dart';
import '../theme/pointly_theme.dart';
import '../widgets/bilingual.dart';
import '../widgets/ui.dart';

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
    final s = await PointlyChannel.getMetricsSummary();
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
      await PointlyChannel.saveSnapshot();
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(S.metSnapshotHint.text)));
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('$e')));
    }
  }

  @override
  Widget build(BuildContext context) {
    Lang.watch(context);
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
        title: Text(S.metTitle.text),
        actions: [
          IconButton(icon: const Icon(Icons.refresh_rounded), tooltip: S.metRefresh.text, onPressed: _load),
          const SizedBox(width: 8),
        ],
      ),
      body: ListView(
        padding: const EdgeInsets.fromLTRB(20, 4, 20, 32),
        children: [
          BiText(S.metNote, size: 16, bold: false, color: Palette.inkSoft),
          const SizedBox(height: 18),
          LayoutBuilder(
            builder: (context, box) {
              final w = (box.maxWidth - 12) / 2;
              return Wrap(
                spacing: 12,
                runSpacing: 12,
                children: [
                  for (var i = 0; i < rows.length; i++)
                    SizedBox(
                      width: w,
                      child: Reveal(
                        index: i,
                        child: _Stat(label: rows[i].$1, value: rows[i].$2),
                      ),
                    ),
                ],
              );
            },
          ),
          const SizedBox(height: 20),
          BigButton(label: S.metExport, icon: Icons.ios_share_rounded, onPressed: () => PointlyChannel.exportMetrics()),
          const SizedBox(height: 28),
          SectionLabel(S.metDebug),
          BigButton(
            label: S.metSaveSnapshot,
            icon: Icons.save_alt_rounded,
            tone: ButtonTone.secondary,
            onPressed: _snapshot,
          ),
          const SizedBox(height: 10),
          BiText(S.metSnapshotHint, size: 15, bold: false, color: Palette.inkSoft),
        ],
      ),
    );
  }
}

class _Stat extends StatelessWidget {
  const _Stat({required this.label, required this.value});

  final Bi label;
  final String value;

  @override
  Widget build(BuildContext context) {
    final empty = value == '—';
    return Card(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(16, 14, 16, 16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            ConstrainedBox(
              constraints: BoxConstraints(minHeight: Lang.isUrdu ? 56 : 44),
              child: BiText(label, size: 14, bold: false, color: Palette.inkSoft),
            ),
            const SizedBox(height: 6),
            Text(
              value,
              style: TextStyle(
                fontSize: 30,
                fontWeight: FontWeight.w700,
                letterSpacing: -0.8,
                color: empty ? Palette.inkFaint : Palette.ink,
                fontFeatures: const [FontFeature.tabularFigures()],
              ),
            ),
          ],
        ),
      ),
    );
  }
}
