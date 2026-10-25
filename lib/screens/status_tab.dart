import 'package:flutter/material.dart';

import '../models/status_item.dart';
import '../theme/app_theme.dart';
import '../utils/time_format.dart';
import '../widgets/avatar.dart';

/// تبويب الحالة (Status): حالتي + تحديثات الآخرين.
class StatusTab extends StatelessWidget {
  const StatusTab({super.key, required this.statuses});

  final List<StatusItem> statuses;

  void _showDemo(BuildContext context, String text) {
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text(text)),
    );
  }

  @override
  Widget build(BuildContext context) {
    final Color secondary = AppColors.secondaryText(context);

    return ListView(
      children: <Widget>[
        ListTile(
          contentPadding:
              const EdgeInsets.symmetric(horizontal: 16, vertical: 6),
          leading: const _MyStatusAvatar(),
          title: const Text(
            'حالتي',
            style: TextStyle(fontWeight: FontWeight.w600),
          ),
          subtitle: Text(
            'اضغط لإضافة تحديث للحالة',
            style: TextStyle(fontSize: 13, color: secondary),
          ),
          trailing: IconButton(
            icon: Icon(Icons.edit_outlined, size: 20, color: secondary),
            tooltip: 'تعديل الحالة',
            onPressed: () => _showDemo(context, 'إضافة تحديث للحالة — تجريبي'),
          ),
          onTap: () => _showDemo(context, 'إضافة تحديث للحالة — تجريبي'),
        ),
        Padding(
          padding: const EdgeInsets.fromLTRB(16, 14, 16, 6),
          child: Text(
            'التحديثات الأخيرة',
            style: TextStyle(
              fontSize: 13,
              fontWeight: FontWeight.w600,
              color: secondary,
            ),
          ),
        ),
        ...statuses.map((StatusItem item) {
          return ListTile(
            leading: Container(
              padding: const EdgeInsets.all(3),
              decoration: BoxDecoration(
                shape: BoxShape.circle,
                border: Border.all(
                  color: item.viewed ? secondary.withAlpha(90) : AppColors.teal,
                  width: 2,
                ),
              ),
              child: Avatar(name: item.name, size: 44),
            ),
            title: Text(
              item.name,
              style: const TextStyle(fontWeight: FontWeight.w600),
            ),
            subtitle: Text(
              formatTimeAgo(item.time),
              style: TextStyle(fontSize: 13, color: secondary),
            ),
            onTap: () => _showDemo(context, 'عرض حالة ${item.name} — تجريبي'),
          );
        }),
      ],
    );
  }
}

class _MyStatusAvatar extends StatelessWidget {
  const _MyStatusAvatar();

  @override
  Widget build(BuildContext context) {
    return Stack(
      clipBehavior: Clip.none,
      children: <Widget>[
        const Avatar(name: 'أنا', size: 52),
        Positioned(
          bottom: 0,
          right: 0,
          child: Container(
            width: 18,
            height: 18,
            decoration: BoxDecoration(
              color: AppColors.teal,
              shape: BoxShape.circle,
              border: Border.all(
                color: AppColors.surfaceAlt(context),
                width: 2,
              ),
            ),
            child: const Icon(Icons.add, size: 12, color: Colors.white),
          ),
        ),
      ],
    );
  }
}
