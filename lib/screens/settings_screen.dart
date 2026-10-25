import 'package:flutter/material.dart';

import '../theme/app_theme.dart';
import '../theme/theme_controller.dart';
import '../widgets/avatar.dart';

/// شاشة الإعدادات: اختيار المظهر + معلومات بسيطة.
class SettingsScreen extends StatelessWidget {
  const SettingsScreen({super.key, required this.themeController});

  final ThemeController themeController;

  @override
  Widget build(BuildContext context) {
    final Color secondary = AppColors.secondaryText(context);

    return Scaffold(
      appBar: AppBar(title: const Text('الإعدادات')),
      body: ListView(
        children: <Widget>[
          const SizedBox(height: 8),
          const ListTile(
            leading: Avatar(name: 'أنا', size: 52),
            title: Text(
              'أنا',
              style: TextStyle(fontWeight: FontWeight.w600),
            ),
            subtitle: Text('+20 100 000 0000'),
          ),
          const Divider(),
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 12, 16, 4),
            child: Text(
              'المظهر',
              style: TextStyle(
                fontSize: 13,
                fontWeight: FontWeight.w600,
                color: secondary,
              ),
            ),
          ),
          _ThemeOption(
            controller: themeController,
            mode: ThemeMode.system,
            title: 'حسب إعدادات النظام',
            icon: Icons.brightness_auto_outlined,
          ),
          _ThemeOption(
            controller: themeController,
            mode: ThemeMode.light,
            title: 'فاتح',
            icon: Icons.light_mode_outlined,
          ),
          _ThemeOption(
            controller: themeController,
            mode: ThemeMode.dark,
            title: 'داكن',
            icon: Icons.dark_mode_outlined,
          ),
          const Divider(),
          const ListTile(
            leading: Icon(Icons.info_outline),
            title: Text('حول التطبيق'),
            subtitle: Text('Conanty 1.0.0 — نسخة تجريبية'),
          ),
        ],
      ),
    );
  }
}

class _ThemeOption extends StatelessWidget {
  const _ThemeOption({
    required this.controller,
    required this.mode,
    required this.title,
    required this.icon,
  });

  final ThemeController controller;
  final ThemeMode mode;
  final String title;
  final IconData icon;

  @override
  Widget build(BuildContext context) {
    return ValueListenableBuilder<ThemeMode>(
      valueListenable: controller,
      builder: (BuildContext context, ThemeMode current, Widget? child) {
        final bool selected = current == mode;
        return ListTile(
          leading: Icon(icon),
          title: Text(title),
          trailing: selected
              ? const Icon(Icons.check_circle, color: AppColors.teal)
              : null,
          onTap: () => controller.value = mode,
        );
      },
    );
  }
}
