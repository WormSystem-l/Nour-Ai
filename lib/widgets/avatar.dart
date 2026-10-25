import 'package:flutter/material.dart';

import '../theme/app_theme.dart';

/// صورة رمزية بحروف الاسم الأولى (مفيش ملفات صور، فمفيش مشاكل assets).
class Avatar extends StatelessWidget {
  const Avatar({
    super.key,
    required this.name,
    this.size = 48,
    this.online = false,
  });

  final String name;
  final double size;
  final bool online;

  static const List<Color> _palette = <Color>[
    Color(0xFF6A8CAF),
    Color(0xFF8C7BB0),
    Color(0xFFB07B7B),
    Color(0xFF7BAF8C),
    Color(0xFFAF9F6A),
    Color(0xFF6AAFAF),
  ];

  Color get _background {
    int hash = 0;
    for (final int unit in name.codeUnits) {
      hash = (hash * 31 + unit) & 0x7fffffff;
    }
    return _palette[hash % _palette.length];
  }

  String get _initials {
    final List<String> parts =
        name.trim().split(RegExp(r'\s+')).where((String p) => p.isNotEmpty).toList();
    if (parts.isEmpty) {
      return '?';
    }
    if (parts.length == 1) {
      return parts.first.substring(0, 1);
    }
    return '${parts.first.substring(0, 1)}${parts[1].substring(0, 1)}';
  }

  @override
  Widget build(BuildContext context) {
    final Widget circle = CircleAvatar(
      radius: size / 2,
      backgroundColor: _background,
      child: Text(
        _initials,
        style: TextStyle(
          color: Colors.white,
          fontSize: size * 0.36,
          fontWeight: FontWeight.w600,
        ),
      ),
    );

    if (!online) {
      return circle;
    }

    return Stack(
      clipBehavior: Clip.none,
      children: <Widget>[
        circle,
        Positioned(
          bottom: 0,
          right: 0,
          child: Container(
            width: size * 0.28,
            height: size * 0.28,
            decoration: BoxDecoration(
              color: AppColors.teal,
              shape: BoxShape.circle,
              border: Border.all(
                color: AppColors.surfaceAlt(context),
                width: 2,
              ),
            ),
          ),
        ),
      ],
    );
  }
}
