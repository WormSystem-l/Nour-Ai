import 'package:flutter/material.dart';

/// لوحة ألوان مستوحاة من واتساب، مع نسخة فاتحة وداكنة.
class AppColors {
  const AppColors._();

  static const Color teal = Color(0xFF00A884);
  static const Color tealDark = Color(0xFF008069);

  static const Color lightBackground = Color(0xFFEFEAE3);
  static const Color lightSurface = Color(0xFFFFFFFF);
  static const Color lightComposer = Color(0xFFF0F2F5);

  static const Color darkBackground = Color(0xFF0B141A);
  static const Color darkSurface = Color(0xFF111B21);
  static const Color darkSurfaceAlt = Color(0xFF202C33);
  static const Color darkComposer = Color(0xFF202C33);

  static const Color bubbleOutLight = Color(0xFFD9FDD3);
  static const Color bubbleOutDark = Color(0xFF005C4B);
  static const Color bubbleInLight = Color(0xFFFFFFFF);
  static const Color bubbleInDark = Color(0xFF202C33);

  static const Color tickBlue = Color(0xFF53BDEB);
  static const Color mutedText = Color(0xFF667781);
  static const Color mutedTextDark = Color(0xFF8696A0);
  static const Color errorRed = Color(0xFFF15C6D);

  static bool isDark(BuildContext context) =>
      Theme.of(context).brightness == Brightness.dark;

  static Color chatBackground(BuildContext context) =>
      isDark(context) ? darkBackground : lightBackground;

  static Color bubbleIn(BuildContext context) =>
      isDark(context) ? bubbleInDark : bubbleInLight;

  static Color bubbleOut(BuildContext context) =>
      isDark(context) ? bubbleOutDark : bubbleOutLight;

  static Color bubbleText(BuildContext context) =>
      isDark(context) ? const Color(0xFFE9EDEF) : const Color(0xFF111B21);

  static Color secondaryText(BuildContext context) =>
      isDark(context) ? mutedTextDark : mutedText;

  static Color surfaceAlt(BuildContext context) =>
      isDark(context) ? darkSurfaceAlt : const Color(0xFFFFFFFF);

  static Color composer(BuildContext context) =>
      isDark(context) ? darkComposer : lightComposer;
}

/// تعريف الثيمين الفاتح والداكن.
class AppTheme {
  const AppTheme._();

  static ThemeData light() {
    final ColorScheme scheme =
        ColorScheme.fromSeed(seedColor: AppColors.teal).copyWith(
      primary: AppColors.tealDark,
      surface: AppColors.lightSurface,
    );
    return _base(scheme, AppColors.lightBackground, AppColors.tealDark);
  }

  static ThemeData dark() {
    final ColorScheme scheme = ColorScheme.fromSeed(
      seedColor: AppColors.teal,
      brightness: Brightness.dark,
    ).copyWith(
      primary: AppColors.teal,
      surface: AppColors.darkSurface,
    );
    return _base(scheme, AppColors.darkBackground, AppColors.darkSurface);
  }

  static ThemeData _base(
    ColorScheme scheme,
    Color background,
    Color appBarColor,
  ) {
    return ThemeData(
      useMaterial3: true,
      colorScheme: scheme,
      scaffoldBackgroundColor: background,
      appBarTheme: AppBarTheme(
        backgroundColor: appBarColor,
        foregroundColor: Colors.white,
        elevation: 0,
        scrolledUnderElevation: 0,
        titleTextStyle: const TextStyle(
          color: Colors.white,
          fontSize: 20,
          fontWeight: FontWeight.w600,
        ),
      ),
      floatingActionButtonTheme: const FloatingActionButtonThemeData(
        backgroundColor: AppColors.teal,
        foregroundColor: Colors.white,
      ),
      snackBarTheme: const SnackBarThemeData(
        behavior: SnackBarBehavior.floating,
        backgroundColor: AppColors.darkSurfaceAlt,
        contentTextStyle: TextStyle(color: Colors.white),
      ),
      dividerTheme: const DividerThemeData(thickness: 0.5, space: 0.5),
    );
  }
}
