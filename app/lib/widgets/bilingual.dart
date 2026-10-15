import 'package:flutter/material.dart';

import '../l10n/strings.dart';
import '../theme/pointly_theme.dart';

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
    final c = color ?? DefaultTextStyle.of(context).style.color ?? Theme.of(context).colorScheme.onSurface;
    final urdu = Lang.isUrdu;
    return Text(
      text.text,
      textDirection: Lang.direction,
      textAlign: center ? TextAlign.center : TextAlign.start,
      style: TextStyle(
        fontSize: urdu ? size * 1.12 : size,
        fontWeight: bold ? FontWeight.w600 : (urdu ? FontWeight.w500 : FontWeight.w400),
        color: c,
        height: urdu ? 1.65 : 1.35,
        letterSpacing: urdu || size < 20 ? 0 : -0.15,
      ),
    );
  }
}

enum ButtonTone { primary, secondary, danger }

class BigButton extends StatelessWidget {
  const BigButton({
    super.key,
    required this.label,
    required this.onPressed,
    this.icon,
    this.tone = ButtonTone.primary,
    this.minHeight = 60,
    this.busy = false,
  });

  final Bi label;
  final VoidCallback? onPressed;
  final IconData? icon;
  final ButtonTone tone;
  final double minHeight;
  final bool busy;

  @override
  Widget build(BuildContext context) {
    Lang.watch(context);
    final content = Row(
      mainAxisAlignment: MainAxisAlignment.center,
      mainAxisSize: MainAxisSize.min,
      children: [
        if (busy)
          const SizedBox.square(dimension: 22, child: CircularProgressIndicator(strokeWidth: 2.5))
        else if (icon != null)
          Icon(icon, size: 24),
        if (busy || icon != null) const SizedBox(width: 12),
        Flexible(
          child: Text(
            label.text,
            textAlign: TextAlign.center,
            style: TextStyle(height: Lang.isUrdu ? 1.6 : 1.2),
          ),
        ),
      ],
    );
    final size = WidgetStatePropertyAll(Size(64, minHeight));
    switch (tone) {
      case ButtonTone.primary:
        return FilledButton(
          onPressed: busy ? null : onPressed,
          style: ButtonStyle(minimumSize: size),
          child: content,
        );
      case ButtonTone.secondary:
        return OutlinedButton(
          onPressed: busy ? null : onPressed,
          style: ButtonStyle(minimumSize: size),
          child: content,
        );
      case ButtonTone.danger:
        return OutlinedButton(
          onPressed: busy ? null : onPressed,
          style: OutlinedButton.styleFrom(
            foregroundColor: Palette.danger,
            side: BorderSide(color: Palette.danger.withValues(alpha: 0.6), width: 1.5),
            minimumSize: Size(64, minHeight),
          ),
          child: content,
        );
    }
  }
}
