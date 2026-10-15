import 'dart:math' as math;

import 'package:flutter/material.dart';

import '../theme/pointly_theme.dart';
import 'marks.dart';

class GuideDemo extends StatefulWidget {
  const GuideDemo({super.key, this.height = 230});

  final double height;

  @override
  State<GuideDemo> createState() => _GuideDemoState();
}

class _GuideDemoState extends State<GuideDemo> with SingleTickerProviderStateMixin {
  late final AnimationController _c = AnimationController(vsync: this, duration: const Duration(milliseconds: 3600));

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (Motion.reduced(context)) {
      _c.stop();
      _c.value = 0.62;
    } else if (!_c.isAnimating) {
      _c.repeat();
    }
  }

  @override
  void dispose() {
    _c.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return ExcludeSemantics(
      child: RepaintBoundary(
        child: SizedBox(
          height: widget.height,
          width: double.infinity,
          child: AnimatedBuilder(
            animation: _c,
            builder: (context, _) => CustomPaint(painter: _DemoPainter(_c.value, Directionality.of(context))),
          ),
        ),
      ),
    );
  }
}

class _DemoPainter extends CustomPainter {
  _DemoPainter(this.t, this.dir);

  final double t;
  final TextDirection dir;

  static double _seg(double t, double a, double b) => ((t - a) / (b - a)).clamp(0.0, 1.0);

  @override
  void paint(Canvas canvas, Size size) {
    final cx = size.width / 2;
    final h = size.height;
    canvas.drawCircle(Offset(cx, h * 0.52), h * 0.47, Paint()..color = Palette.brandSoft);

    final phone = RRect.fromRectAndRadius(
      Rect.fromCenter(center: Offset(cx, h * 0.53), width: h * 0.56, height: h * 0.9),
      const Radius.circular(24),
    );
    canvas.drawRRect(
      phone.shift(const Offset(0, 6)),
      Paint()
        ..color = Palette.ink.withValues(alpha: 0.10)
        ..maskFilter = const MaskFilter.blur(BlurStyle.normal, 10),
    );
    canvas.drawRRect(phone, Paint()..color = Palette.surface);
    canvas.drawRRect(
      phone,
      Paint()
        ..style = PaintingStyle.stroke
        ..strokeWidth = 2
        ..color = Palette.line,
    );
    final p = phone.outerRect;
    canvas.drawRRect(
      RRect.fromRectAndRadius(
        Rect.fromCenter(center: Offset(cx, p.top + 12), width: 34, height: 6),
        const Radius.circular(3),
      ),
      Paint()..color = Palette.line,
    );

    final fadeOut = 1 - _seg(t, 0.86, 0.98);
    final rowH = p.height * 0.15;
    final inset = p.width * 0.09;
    Rect row(int i) => Rect.fromLTWH(p.left + inset, p.top + 30 + i * (rowH + 8), p.width - inset * 2, rowH);
    const target = 2;
    final rtl = dir == TextDirection.rtl;

    final success = _seg(t, 0.52, 0.64) * fadeOut;
    for (var i = 0; i < 4; i++) {
      final r = row(i);
      if (i == target && success > 0) {
        canvas.drawRRect(
          RRect.fromRectAndRadius(r.inflate(3), const Radius.circular(10)),
          Paint()..color = Palette.brandSoft.withValues(alpha: success),
        );
      }
      final avatarX = rtl ? r.right - rowH * 0.42 : r.left + rowH * 0.42;
      canvas.drawCircle(
        Offset(avatarX, r.center.dy),
        rowH * 0.3,
        Paint()..color = i == target ? Palette.signalSoft : Palette.paper,
      );
      final lineStart = rtl ? r.left : r.left + rowH * 0.9;
      final lineEnd = rtl ? r.right - rowH * 0.9 : r.right;
      final bar = Paint()..color = Palette.line;
      canvas.drawRRect(
        RRect.fromLTRBR(
          lineStart,
          r.center.dy - 7,
          lineStart + (lineEnd - lineStart) * 0.78,
          r.center.dy - 1,
          const Radius.circular(3),
        ),
        bar,
      );
      canvas.drawRRect(
        RRect.fromLTRBR(
          lineStart,
          r.center.dy + 4,
          lineStart + (lineEnd - lineStart) * 0.5,
          r.center.dy + 9,
          const Radius.circular(3),
        ),
        Paint()..color = Palette.line.withValues(alpha: 0.7),
      );
    }

    final r = row(target);
    final lock = _seg(t, 0.26, 0.42);
    if (lock > 0 && fadeOut > 0) {
      final eased = Curves.easeOutBack.transform(lock);
      final frame = RRect.fromRectAndRadius(r.inflate(5 + (1 - eased) * 16), const Radius.circular(12));
      final a = (lock * 1.6).clamp(0.0, 1.0) * fadeOut;
      canvas.drawRRect(
        frame,
        Paint()
          ..style = PaintingStyle.stroke
          ..strokeWidth = 7
          ..color = Palette.signal.withValues(alpha: 0.22 * a),
      );
      canvas.drawRRect(
        frame,
        Paint()
          ..style = PaintingStyle.stroke
          ..strokeWidth = 3
          ..color = Palette.signal.withValues(alpha: a),
      );
    }

    if (success > 0) {
      final pop = Curves.easeOutBack.transform(_seg(t, 0.56, 0.7));
      final at = Offset(rtl ? r.left + 16 : r.right - 16, r.center.dy);
      canvas.drawCircle(at, 11 * pop, Paint()..color = Palette.brand.withValues(alpha: fadeOut));
      final tick = Path()
        ..moveTo(at.dx - 5 * pop, at.dy)
        ..lineTo(at.dx - 1.5 * pop, at.dy + 3.5 * pop)
        ..lineTo(at.dx + 5 * pop, at.dy - 3.5 * pop);
      canvas.drawPath(
        tick,
        Paint()
          ..style = PaintingStyle.stroke
          ..strokeWidth = 2.4
          ..strokeCap = StrokeCap.round
          ..strokeJoin = StrokeJoin.round
          ..color = Colors.white.withValues(alpha: fadeOut),
      );
    }

    final tipTarget = Offset(r.left + r.width * (rtl ? 0.38 : 0.62), r.center.dy);
    final start = Offset(rtl ? -20 : size.width + 20, h + 30);
    final control = Offset(rtl ? cx - h * 0.55 : cx + h * 0.55, h * 0.45);
    final fly = Curves.easeInOutCubic.transform(_seg(t, 0.02, 0.32));
    final u = 1 - fly;
    final tip = start * (u * u) + control * (2 * u * fly) + tipTarget * (fly * fly);

    final press = _seg(t, 0.42, 0.5);
    final release = _seg(t, 0.5, 0.58);
    final scale = 1 - 0.1 * math.sin(math.pi * (press * 0.5 + release * 0.5));
    final ripple = _seg(t, 0.46, 0.74);
    if (ripple > 0 && ripple < 1) {
      final e = Curves.easeOut.transform(ripple);
      canvas.drawCircle(
        tipTarget,
        6 + 26 * e,
        Paint()
          ..style = PaintingStyle.stroke
          ..strokeWidth = 2.5
          ..color = Palette.signal.withValues(alpha: (1 - e) * 0.9),
      );
    }
    final handOpacity = _seg(t, 0.02, 0.1) * fadeOut;
    HandShape.paint(canvas, tip, scale: scale, opacity: handOpacity, unit: h / 330, tilt: rtl ? 10 : -10);
  }

  @override
  bool shouldRepaint(_DemoPainter old) => old.t != t || old.dir != dir;
}

class BubbleHalo extends StatefulWidget {
  const BubbleHalo({super.key, required this.active, required this.size});

  final bool active;
  final double size;

  @override
  State<BubbleHalo> createState() => _BubbleHaloState();
}

class _BubbleHaloState extends State<BubbleHalo> with SingleTickerProviderStateMixin {
  late final AnimationController _c = AnimationController(vsync: this, duration: const Duration(milliseconds: 2600));

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    _sync();
  }

  @override
  void didUpdateWidget(BubbleHalo old) {
    super.didUpdateWidget(old);
    _sync();
  }

  void _sync() {
    final run = widget.active && !Motion.reduced(context);
    if (run && !_c.isAnimating) {
      _c.repeat();
    } else if (!run && _c.isAnimating) {
      _c.stop();
    }
  }

  @override
  void dispose() {
    _c.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return RepaintBoundary(
      child: AnimatedOpacity(
        opacity: widget.active ? 1 : 0,
        duration: Motion.of(context, Motion.slow),
        child: AnimatedBuilder(
          animation: _c,
          builder: (context, _) =>
              CustomPaint(size: Size.square(widget.size), painter: _HaloPainter(_c.value, Motion.reduced(context))),
        ),
      ),
    );
  }
}

class _HaloPainter extends CustomPainter {
  _HaloPainter(this.t, this.still);

  final double t;
  final bool still;

  @override
  void paint(Canvas canvas, Size size) {
    final c = size.center(Offset.zero);
    final r0 = size.shortestSide * 0.31;
    final span = size.shortestSide * 0.19;
    if (still) {
      canvas.drawCircle(c, r0 + span * 0.45, Paint()..color = Palette.brand.withValues(alpha: 0.10));
      return;
    }
    for (final offset in [0.0, 0.5]) {
      final p = (t + offset) % 1;
      final e = Curves.easeOut.transform(p);
      canvas.drawCircle(c, r0 + span * e, Paint()..color = Palette.brand.withValues(alpha: 0.16 * (1 - p)));
    }
  }

  @override
  bool shouldRepaint(_HaloPainter old) => old.t != t || old.still != still;
}
