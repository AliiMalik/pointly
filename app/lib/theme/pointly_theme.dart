import 'package:flutter/material.dart';

class Palette {
  Palette._();

  static const ink = Color(0xFF13201B);
  static const inkSoft = Color(0xFF55615B);
  static const inkFaint = Color(0xFF8A938E);
  static const paper = Color(0xFFF6F4EF);
  static const surface = Color(0xFFFFFFFF);
  static const line = Color(0xFFE6E1D6);
  static const brand = Color(0xFF0E5E43);
  static const brandDeep = Color(0xFF0A4632);
  static const brandBright = Color(0xFF178A62);
  static const brandSoft = Color(0xFFE2EEE8);
  static const signal = Color(0xFFF2A93B);
  static const signalSoft = Color(0xFFFCEFD9);
  static const signalInk = Color(0xFF7A4A06);
  static const danger = Color(0xFFB42318);
  static const dangerSoft = Color(0xFFFBEAE8);
}

class Motion {
  Motion._();

  static const fast = Duration(milliseconds: 160);
  static const base = Duration(milliseconds: 280);
  static const slow = Duration(milliseconds: 450);
  static const stagger = Duration(milliseconds: 45);

  static const enter = Curves.easeOutCubic;
  static const exit = Curves.easeInCubic;
  static const spring = Curves.easeOutBack;

  static bool reduced(BuildContext context) => MediaQuery.maybeDisableAnimationsOf(context) ?? false;

  static Duration of(BuildContext context, Duration d) => reduced(context) ? Duration.zero : d;
}

class Radii {
  Radii._();

  static const sm = 12.0;
  static const md = 16.0;
  static const lg = 22.0;
  static const xl = 28.0;
}

class PointlyTheme {
  PointlyTheme._();

  static ThemeData light() {
    const scheme = ColorScheme(
      brightness: Brightness.light,
      primary: Palette.brand,
      onPrimary: Colors.white,
      primaryContainer: Palette.brandSoft,
      onPrimaryContainer: Palette.brandDeep,
      secondary: Palette.signal,
      onSecondary: Palette.ink,
      secondaryContainer: Palette.signalSoft,
      onSecondaryContainer: Palette.signalInk,
      error: Palette.danger,
      onError: Colors.white,
      errorContainer: Palette.dangerSoft,
      onErrorContainer: Palette.danger,
      surface: Palette.surface,
      onSurface: Palette.ink,
      onSurfaceVariant: Palette.inkSoft,
      outline: Palette.line,
      outlineVariant: Palette.line,
      surfaceContainerHighest: Palette.paper,
    );

    const text = TextTheme(
      displaySmall: TextStyle(
        fontSize: 32,
        height: 1.15,
        fontWeight: FontWeight.w700,
        letterSpacing: -0.6,
        color: Palette.ink,
      ),
      headlineSmall: TextStyle(
        fontSize: 26,
        height: 1.2,
        fontWeight: FontWeight.w700,
        letterSpacing: -0.3,
        color: Palette.ink,
      ),
      titleLarge: TextStyle(
        fontSize: 21,
        height: 1.25,
        fontWeight: FontWeight.w700,
        letterSpacing: -0.2,
        color: Palette.ink,
      ),
      titleMedium: TextStyle(fontSize: 18, height: 1.3, fontWeight: FontWeight.w600, color: Palette.ink),
      bodyLarge: TextStyle(fontSize: 18, height: 1.45, color: Palette.ink),
      bodyMedium: TextStyle(fontSize: 16, height: 1.45, color: Palette.inkSoft),
      labelLarge: TextStyle(fontSize: 17, fontWeight: FontWeight.w600, letterSpacing: 0.1),
      labelMedium: TextStyle(fontSize: 14, fontWeight: FontWeight.w700, letterSpacing: 0.9, color: Palette.inkSoft),
    );

    final buttonShape = RoundedRectangleBorder(borderRadius: BorderRadius.circular(Radii.md));

    return ThemeData(
      useMaterial3: true,
      colorScheme: scheme,
      textTheme: text,
      scaffoldBackgroundColor: Palette.paper,
      splashFactory: InkSparkle.splashFactory,
      appBarTheme: const AppBarTheme(
        backgroundColor: Palette.paper,
        foregroundColor: Palette.ink,
        surfaceTintColor: Colors.transparent,
        elevation: 0,
        scrolledUnderElevation: 0,
        centerTitle: false,
        titleTextStyle: TextStyle(fontSize: 20, fontWeight: FontWeight.w700, color: Palette.ink, letterSpacing: -0.2),
      ),
      cardTheme: CardThemeData(
        color: Palette.surface,
        surfaceTintColor: Colors.transparent,
        elevation: 0,
        margin: EdgeInsets.zero,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(Radii.lg),
          side: const BorderSide(color: Palette.line),
        ),
      ),
      filledButtonTheme: FilledButtonThemeData(
        style: FilledButton.styleFrom(
          backgroundColor: Palette.brand,
          foregroundColor: Colors.white,
          disabledBackgroundColor: Palette.line,
          disabledForegroundColor: Palette.inkFaint,
          minimumSize: const Size(64, 60),
          padding: const EdgeInsets.symmetric(horizontal: 22, vertical: 14),
          shape: buttonShape,
          textStyle: text.labelLarge,
        ),
      ),
      outlinedButtonTheme: OutlinedButtonThemeData(
        style: OutlinedButton.styleFrom(
          foregroundColor: Palette.brand,
          minimumSize: const Size(64, 56),
          padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 12),
          side: const BorderSide(color: Color(0xFF7FA595), width: 1.5),
          shape: buttonShape,
          textStyle: text.labelLarge,
        ),
      ),
      textButtonTheme: TextButtonThemeData(
        style: TextButton.styleFrom(
          foregroundColor: Palette.brand,
          minimumSize: const Size(48, 48),
          textStyle: text.labelLarge,
        ),
      ),
      iconButtonTheme: IconButtonThemeData(
        style: IconButton.styleFrom(foregroundColor: Palette.ink, minimumSize: const Size(52, 52), iconSize: 26),
      ),
      segmentedButtonTheme: SegmentedButtonThemeData(
        style: SegmentedButton.styleFrom(
          backgroundColor: Palette.surface,
          foregroundColor: Palette.inkSoft,
          selectedBackgroundColor: Palette.brand,
          selectedForegroundColor: Colors.white,
          side: const BorderSide(color: Color(0xFF9FB3AA), width: 1.5),
          minimumSize: const Size(0, 56),
          textStyle: const TextStyle(fontSize: 17, fontWeight: FontWeight.w600),
          shape: buttonShape,
        ),
      ),
      switchTheme: SwitchThemeData(
        thumbColor: WidgetStateProperty.resolveWith(
          (s) => s.contains(WidgetState.selected) ? Colors.white : Palette.inkFaint,
        ),
        trackColor: WidgetStateProperty.resolveWith(
          (s) => s.contains(WidgetState.selected) ? Palette.brand : Palette.line,
        ),
        trackOutlineColor: WidgetStateProperty.resolveWith(
          (s) => s.contains(WidgetState.selected) ? Colors.transparent : Palette.inkFaint,
        ),
      ),
      inputDecorationTheme: InputDecorationTheme(
        filled: true,
        fillColor: Palette.surface,
        contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 16),
        hintStyle: const TextStyle(color: Palette.inkSoft),
        border: OutlineInputBorder(
          borderRadius: BorderRadius.circular(Radii.sm),
          borderSide: const BorderSide(color: Palette.line, width: 1.5),
        ),
        enabledBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(Radii.sm),
          borderSide: const BorderSide(color: Palette.line, width: 1.5),
        ),
        focusedBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(Radii.sm),
          borderSide: const BorderSide(color: Palette.brand, width: 2),
        ),
      ),
      snackBarTheme: SnackBarThemeData(
        behavior: SnackBarBehavior.floating,
        backgroundColor: Palette.ink,
        contentTextStyle: const TextStyle(fontSize: 16, color: Colors.white, height: 1.35),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(Radii.md)),
        insetPadding: const EdgeInsets.fromLTRB(16, 0, 16, 16),
      ),
      progressIndicatorTheme: const ProgressIndicatorThemeData(
        color: Palette.brand,
        linearTrackColor: Palette.brandSoft,
      ),
      dividerTheme: const DividerThemeData(color: Palette.line, thickness: 1, space: 1),
    );
  }
}
