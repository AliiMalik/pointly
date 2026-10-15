import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../l10n/strings.dart';
import '../theme/pointly_theme.dart';
import 'bilingual.dart';

class Reveal extends StatelessWidget {
  const Reveal({super.key, required this.index, required this.child, this.offset = 14});

  final int index;
  final Widget child;
  final double offset;

  @override
  Widget build(BuildContext context) {
    if (Motion.reduced(context)) return child;
    final delay = Motion.stagger * index;
    final total = Motion.slow + delay;
    final start = delay.inMilliseconds / total.inMilliseconds;
    return TweenAnimationBuilder<double>(
      tween: Tween(begin: 0, end: 1),
      duration: total,
      curve: Interval(start, 1, curve: Motion.enter),
      builder: (context, t, child) => Opacity(
        opacity: t,
        child: Transform.translate(offset: Offset(0, (1 - t) * offset), child: child),
      ),
      child: child,
    );
  }
}

class Pressable extends StatefulWidget {
  const Pressable({
    super.key,
    required this.child,
    required this.onTap,
    this.color = Palette.surface,
    this.radius = Radii.lg,
    this.border = true,
    this.padding = EdgeInsets.zero,
    this.semanticLabel,
  });

  final Widget child;
  final VoidCallback? onTap;
  final Color color;
  final double radius;
  final bool border;
  final EdgeInsetsGeometry padding;
  final String? semanticLabel;

  @override
  State<Pressable> createState() => _PressableState();
}

class _PressableState extends State<Pressable> {
  bool _down = false;

  @override
  Widget build(BuildContext context) {
    final shape = RoundedRectangleBorder(
      borderRadius: BorderRadius.circular(widget.radius),
      side: widget.border ? const BorderSide(color: Palette.line) : BorderSide.none,
    );
    return Semantics(
      button: true,
      label: widget.semanticLabel,
      child: AnimatedScale(
        scale: _down ? 0.975 : 1,
        duration: Motion.of(context, Motion.fast),
        curve: Motion.enter,
        child: Material(
          color: widget.color,
          shape: shape,
          clipBehavior: Clip.antiAlias,
          child: InkWell(
            onTap: widget.onTap == null
                ? null
                : () {
                    HapticFeedback.selectionClick();
                    widget.onTap!();
                  },
            onHighlightChanged: (v) => setState(() => _down = v),
            child: Padding(padding: widget.padding, child: widget.child),
          ),
        ),
      ),
    );
  }
}

class SmoothSize extends StatelessWidget {
  const SmoothSize({super.key, required this.child, this.alignment = AlignmentDirectional.topStart});

  final Widget child;
  final AlignmentGeometry alignment;

  @override
  Widget build(BuildContext context) {
    if (Motion.reduced(context)) return child;
    return AnimatedSize(duration: Motion.base, curve: Motion.enter, alignment: alignment, child: child);
  }
}

class SectionLabel extends StatelessWidget {
  const SectionLabel(this.text, {super.key});

  final Bi text;

  @override
  Widget build(BuildContext context) {
    Lang.watch(context);
    final style = Theme.of(context).textTheme.labelMedium!;
    return Semantics(
      header: true,
      child: Padding(
        padding: const EdgeInsetsDirectional.only(start: 4, bottom: 10, top: 4),
        child: Text(
          Lang.isUrdu ? text.ur : text.en.toUpperCase(),
          style: Lang.isUrdu
              ? style.copyWith(letterSpacing: 0, fontSize: 17, height: 1.6)
              : style.copyWith(fontSize: 15),
        ),
      ),
    );
  }
}

class IconBadge extends StatelessWidget {
  const IconBadge(
    this.icon, {
    super.key,
    this.size = 48,
    this.color = Palette.brand,
    this.background = Palette.brandSoft,
  });

  final IconData icon;
  final double size;
  final Color color;
  final Color background;

  @override
  Widget build(BuildContext context) {
    return Container(
      width: size,
      height: size,
      decoration: BoxDecoration(color: background, borderRadius: BorderRadius.circular(size * 0.32)),
      alignment: Alignment.center,
      child: ExcludeSemantics(
        child: Icon(icon, color: color, size: size * 0.52),
      ),
    );
  }
}

class ActionTile extends StatelessWidget {
  const ActionTile({super.key, required this.icon, required this.title, required this.onTap, this.subtitle});

  final IconData icon;
  final Bi title;
  final Bi? subtitle;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return Pressable(
      onTap: onTap,
      padding: const EdgeInsetsDirectional.fromSTEB(14, 14, 10, 14),
      child: ConstrainedBox(
        constraints: const BoxConstraints(minHeight: 52),
        child: Row(
          children: [
            IconBadge(icon),
            const SizedBox(width: 16),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                mainAxisSize: MainAxisSize.min,
                children: [
                  BiText(title, size: 18, color: Palette.ink),
                  if (subtitle != null) ...[
                    const SizedBox(height: 2),
                    BiText(subtitle!, size: 15, bold: false, color: Palette.inkSoft),
                  ],
                ],
              ),
            ),
            const ExcludeSemantics(child: Icon(Icons.chevron_right_rounded, color: Palette.inkFaint, size: 28)),
          ],
        ),
      ),
    );
  }
}

enum BannerTone { info, warning, danger }

class InfoBanner extends StatelessWidget {
  const InfoBanner({
    super.key,
    required this.icon,
    required this.text,
    this.action,
    this.onTap,
    this.tone = BannerTone.warning,
  });

  final IconData icon;
  final Bi text;
  final Bi? action;
  final VoidCallback? onTap;
  final BannerTone tone;

  @override
  Widget build(BuildContext context) {
    final (bg, fg) = switch (tone) {
      BannerTone.info => (Palette.brandSoft, Palette.brandDeep),
      BannerTone.warning => (Palette.signalSoft, Palette.signalInk),
      BannerTone.danger => (Palette.dangerSoft, Palette.danger),
    };
    return Pressable(
      onTap: onTap,
      color: bg,
      border: false,
      radius: Radii.md,
      padding: const EdgeInsetsDirectional.fromSTEB(14, 14, 12, 14),
      child: Row(
        children: [
          ExcludeSemantics(child: Icon(icon, color: fg, size: 26)),
          const SizedBox(width: 12),
          Expanded(child: BiText(text, size: 16, color: fg)),
          if (action != null) ...[
            const SizedBox(width: 8),
            BiText(action!, size: 16, color: fg),
            ExcludeSemantics(child: Icon(Icons.chevron_right_rounded, color: fg)),
          ],
        ],
      ),
    );
  }
}

class StatusPill extends StatefulWidget {
  const StatusPill({super.key, required this.state});

  final String state;

  static bool busy(String s) => const {'LISTENING', 'TRANSCRIBING', 'READING_SCREEN', 'PLANNING'}.contains(s);

  @override
  State<StatusPill> createState() => _StatusPillState();
}

class _StatusPillState extends State<StatusPill> with SingleTickerProviderStateMixin {
  late final AnimationController _pulse = AnimationController(
    vsync: this,
    duration: const Duration(milliseconds: 1100),
  );

  (Color, Color, IconData?) _tone(String s) => switch (s) {
    'LISTENING' => (Palette.dangerSoft, Palette.danger, Icons.mic_rounded),
    'TRANSCRIBING' || 'READING_SCREEN' || 'PLANNING' || 'ASKING' => (Palette.signalSoft, Palette.signalInk, null),
    'SENSITIVE_PAUSE' => (const Color(0xFFEDEFEE), Palette.inkSoft, Icons.lock_rounded),
    'COMPLETED' => (Palette.brandSoft, Palette.brandDeep, Icons.check_rounded),
    'ERROR' => (Palette.dangerSoft, Palette.danger, Icons.error_outline_rounded),
    _ => (Palette.brandSoft, Palette.brandDeep, null),
  };

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    _sync();
  }

  @override
  void didUpdateWidget(StatusPill old) {
    super.didUpdateWidget(old);
    _sync();
  }

  void _sync() {
    final animate = StatusPill.busy(widget.state) && !Motion.reduced(context);
    if (animate && !_pulse.isAnimating) {
      _pulse.repeat(reverse: true);
    } else if (!animate && _pulse.isAnimating) {
      _pulse.stop();
      _pulse.value = 0;
    }
  }

  @override
  void dispose() {
    _pulse.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    Lang.watch(context);
    final (bg, fg, icon) = _tone(widget.state);
    final label = S.stateLabel(widget.state);
    return Semantics(
      container: true,
      liveRegion: true,
      label: '${S.homeState.text}: ${label.text}',
      child: ExcludeSemantics(
        child: AnimatedContainer(
          duration: Motion.of(context, Motion.base),
          curve: Motion.enter,
          padding: const EdgeInsetsDirectional.fromSTEB(12, 8, 16, 8),
          decoration: BoxDecoration(color: bg, borderRadius: BorderRadius.circular(999)),
          child: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              if (icon != null)
                Icon(icon, size: 18, color: fg)
              else
                AnimatedBuilder(
                  animation: _pulse,
                  builder: (context, _) => Container(
                    width: 10,
                    height: 10,
                    decoration: BoxDecoration(
                      color: fg.withValues(alpha: 1 - 0.55 * _pulse.value),
                      shape: BoxShape.circle,
                      boxShadow: [
                        BoxShadow(
                          color: fg.withValues(alpha: 0.35 * _pulse.value),
                          blurRadius: 8,
                          spreadRadius: 2 * _pulse.value,
                        ),
                      ],
                    ),
                  ),
                ),
              const SizedBox(width: 8),
              Flexible(
                child: AnimatedSwitcher(
                  duration: Motion.of(context, Motion.fast),
                  child: Text(
                    label.text,
                    key: ValueKey(widget.state),
                    style: TextStyle(
                      fontSize: 16,
                      fontWeight: FontWeight.w700,
                      color: fg,
                      height: Lang.isUrdu ? 1.6 : 1.2,
                    ),
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

class StepBadge extends StatelessWidget {
  const StepBadge({super.key, required this.number, required this.done});

  final int number;
  final bool done;

  @override
  Widget build(BuildContext context) {
    return AnimatedContainer(
      duration: Motion.of(context, Motion.base),
      curve: Motion.enter,
      width: 40,
      height: 40,
      decoration: BoxDecoration(
        color: done ? Palette.brand : Palette.surface,
        shape: BoxShape.circle,
        border: Border.all(color: done ? Palette.brand : Palette.line, width: 2),
      ),
      alignment: Alignment.center,
      child: AnimatedSwitcher(
        duration: Motion.of(context, Motion.base),
        transitionBuilder: (child, a) => ScaleTransition(
          scale: CurvedAnimation(parent: a, curve: Motion.spring),
          child: child,
        ),
        child: done
            ? const Icon(Icons.check_rounded, key: ValueKey('done'), color: Colors.white, size: 24)
            : Text(
                '$number',
                key: const ValueKey('n'),
                style: const TextStyle(fontSize: 17, fontWeight: FontWeight.w700, color: Palette.inkSoft),
              ),
      ),
    );
  }
}
