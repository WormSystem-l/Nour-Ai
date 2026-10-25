import 'package:flutter/material.dart';

/// تحكّم في وضع الثيم (فاتح / داكن / حسب النظام).
class ThemeController extends ValueNotifier<ThemeMode> {
  ThemeController([super.value = ThemeMode.system]);
}
