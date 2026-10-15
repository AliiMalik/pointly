import 'dart:math' as math;

import 'package:flutter/material.dart';

import '../theme/pointly_theme.dart';

class PointlyMark extends StatelessWidget {
  const PointlyMark({super.key, this.size = 40, this.muted = false});

  final double size;
  final bool muted;

  @override
  Widget build(BuildContext context) {
    return SizedBox.square(
      dimension: size,
      child: CustomPaint(painter: _MarkPainter(muted)),
    );
  }
}

class _MarkPainter extends CustomPainter {
  _MarkPainter(this.muted);

  final bool muted;

  @override
  void paint(Canvas canvas, Size size) {
    paintMark(canvas, size.center(Offset.zero), size.shortestSide / 2, muted: muted);
  }

  @override
  bool shouldRepaint(_MarkPainter old) => old.muted != muted;
}

void paintMark(Canvas canvas, Offset c, double r, {bool muted = false, double dotScale = 1}) {
  final top = muted ? const Color(0xFF7D8984) : Palette.brandBright;
  final bottom = muted ? const Color(0xFF4E5A55) : Palette.brandDeep;
  final body = Paint()
    ..shader = RadialGradient(
      center: const Alignment(-0.45, -0.55),
      radius: 1.15,
      colors: [top, bottom],
    ).createShader(Rect.fromCircle(center: c, radius: r));
  canvas.drawCircle(c, r, body);
  final sheen = Paint()
    ..shader = LinearGradient(
      begin: Alignment.topCenter,
      end: Alignment.center,
      colors: [Colors.white.withValues(alpha: 0.18), Colors.white.withValues(alpha: 0)],
    ).createShader(Rect.fromCircle(center: c, radius: r));
  canvas.drawCircle(c, r * 0.94, sheen);
  final ring = Paint()
    ..style = PaintingStyle.stroke
    ..strokeWidth = r * 0.15
    ..color = Colors.white;
  canvas.drawCircle(c, r * 0.5, ring);
  canvas.drawCircle(c, r * 0.21 * dotScale, Paint()..color = muted ? Colors.white : Palette.signal);
}

class HandShape {
  HandShape._();

  static const width = 58.0;
  static const height = 84.0;
  static const tip = Offset(20, 0);

  static Path silhouette() {
    Path rr(double l, double t, double r, double b, double rad) =>
        Path()..addRRect(RRect.fromLTRBR(l, t, r, b, Radius.circular(rad)));
    var p = rr(13, 0, 27, 46, 7);
    for (final part in [rr(26, 27, 40, 52, 7), rr(38, 31, 50, 54, 6), rr(48, 36, 58, 56, 5), rr(11, 38, 58, 84, 15)]) {
      p = Path.combine(PathOperation.union, p, part);
    }
    final thumb = rr(-1, 44, 16, 66, 8);
    final m = Matrix4.identity()
      ..translateByDouble(9, 55, 0, 1)
      ..rotateZ(-32 * math.pi / 180)
      ..translateByDouble(-9, -55, 0, 1);
    return Path.combine(PathOperation.union, p, thumb.transform(m.storage));
  }

  static final Path _path = silhouette();

  static void paint(
    Canvas canvas,
    Offset tipAt, {
    double scale = 1,
    double opacity = 1,
    double unit = 1,
    double tilt = -10,
  }) {
    if (opacity <= 0) return;
    canvas.save();
    canvas.translate(tipAt.dx, tipAt.dy);
    canvas.rotate(tilt * math.pi / 180);
    canvas.scale(unit * scale);
    canvas.translate(-tip.dx, -tip.dy);
    final a = opacity.clamp(0.0, 1.0);
    canvas.drawPath(
      _path.shift(const Offset(2.5, 5)),
      Paint()
        ..color = Colors.black.withValues(alpha: 0.22 * a)
        ..maskFilter = const MaskFilter.blur(BlurStyle.normal, 5),
    );
    canvas.drawPath(
      _path,
      Paint()
        ..shader = const LinearGradient(
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
          colors: [Colors.white, Color(0xFFF1EEE8)],
        ).createShader(const Rect.fromLTWH(0, 0, width, height))
        ..color = Colors.white.withValues(alpha: a),
    );
    final crease = Paint()
      ..color = const Color(0xFFCFC9BE).withValues(alpha: a)
      ..strokeWidth = 1.6
      ..strokeCap = StrokeCap.round;
    canvas.drawLine(const Offset(38.5, 35), const Offset(38.5, 43), crease);
    canvas.drawLine(const Offset(48.5, 39), const Offset(48.5, 46), crease);
    canvas.save();
    canvas.clipPath(_path);
    canvas.drawRect(const Rect.fromLTRB(0, 72, width, height), Paint()..color = Palette.signal.withValues(alpha: a));
    canvas.restore();
    canvas.drawPath(
      _path,
      Paint()
        ..style = PaintingStyle.stroke
        ..strokeWidth = 2.4
        ..strokeJoin = StrokeJoin.round
        ..color = Palette.ink.withValues(alpha: a),
    );
    canvas.restore();
  }
}
