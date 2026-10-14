import 'package:flutter/material.dart';

import '../l10n/strings.dart';

class BiText extends StatelessWidget {
  const BiText(this.text, {super.key, this.size = 20, this.color, this.center = false, this.bold = true});

  final Bi text;
  final double size;
  final Color? color;
  final bool center;
  final bool bold;

  @override
  Widget build(BuildContext context) {
    Lang.watch(context);
    final c = color ?? Theme.of(context).colorScheme.onSurface;
    final urdu = Lang.isUrdu;
    return Text(
      text.text,
      textDirection: Lang.direction,
      textAlign: center ? TextAlign.center : TextAlign.start,
      style: TextStyle(
        fontSize: size,
        fontWeight: bold ? FontWeight.w600 : FontWeight.normal,
        color: c,
        height: urdu ? 1.6 : 1.3,
      ),
    );
  }
}

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
