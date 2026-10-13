import 'package:flutter/material.dart';

import '../l10n/strings.dart';

/// Urdu line (right-to-left, larger) above an English line.
class BiText extends StatelessWidget {
  const BiText(this.text, {super.key, this.size = 20, this.color, this.center = false});

  final Bi text;
  final double size;
  final Color? color;
  final bool center;

  @override
  Widget build(BuildContext context) {
    final c = color ?? Theme.of(context).colorScheme.onSurface;
    return Column(
      crossAxisAlignment: center ? CrossAxisAlignment.center : CrossAxisAlignment.stretch,
      mainAxisSize: MainAxisSize.min,
      children: [
        Text(
          text.ur,
          textDirection: TextDirection.rtl,
          textAlign: center ? TextAlign.center : TextAlign.right,
          style: TextStyle(fontSize: size, fontWeight: FontWeight.w600, color: c, height: 1.6),
        ),
        Text(
          text.en,
          textAlign: center ? TextAlign.center : TextAlign.left,
          style: TextStyle(fontSize: size * 0.7, color: c.withValues(alpha: 0.75)),
        ),
      ],
    );
  }
}

/// Large, high-contrast button (at least 56 dp tall) with a bilingual label.
class BigButton extends StatelessWidget {
  const BigButton({
    super.key,
    required this.label,
    required this.onPressed,
    this.icon,
    this.color,
    this.foreground,
    this.minHeight = 72,
  });

  final Bi label;
  final VoidCallback? onPressed;
  final IconData? icon;
  final Color? color;
  final Color? foreground;
  final double minHeight;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final bg = color ?? scheme.primary;
    final fg = foreground ?? scheme.onPrimary;
    return ConstrainedBox(
      constraints: BoxConstraints(minHeight: minHeight),
      child: FilledButton(
        style: FilledButton.styleFrom(
          backgroundColor: bg,
          foregroundColor: fg,
          padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 12),
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
        ),
        onPressed: onPressed,
        child: Row(
          children: [
            if (icon != null) ...[Icon(icon, size: 32), const SizedBox(width: 16)],
            Expanded(child: BiText(label, size: 20, color: fg)),
          ],
        ),
      ),
    );
  }
}
