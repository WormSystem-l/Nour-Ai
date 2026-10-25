import 'package:flutter/material.dart';

import '../models/call_entry.dart';
import '../theme/app_theme.dart';
import '../utils/time_format.dart';
import '../widgets/avatar.dart';

/// تبويب المكالمات.
class CallsTab extends StatelessWidget {
  const CallsTab({super.key, required this.calls});

  final List<CallEntry> calls;

  IconData _iconFor(CallType type, bool isVideo) {
    if (isVideo) {
      return Icons.videocam_outlined;
    }
    switch (type) {
      case CallType.incoming:
        return Icons.call_received;
      case CallType.outgoing:
        return Icons.call_made;
      case CallType.missed:
        return Icons.call_missed;
    }
  }

  Color _colorFor(CallType type, Color secondary) {
    return type == CallType.missed ? AppColors.errorRed : secondary;
  }

  @override
  Widget build(BuildContext context) {
    final Color secondary = AppColors.secondaryText(context);

    return ListView.separated(
      itemCount: calls.length,
      separatorBuilder: (BuildContext context, int index) => Divider(
        height: 1,
        indent: 82,
        color: secondary.withAlpha(38),
      ),
      itemBuilder: (BuildContext context, int index) {
        final CallEntry call = calls[index];
        return ListTile(
          leading: Avatar(name: call.name, size: 50),
          title: Text(
            call.name,
            style: const TextStyle(fontWeight: FontWeight.w600),
          ),
          subtitle: Row(
            children: <Widget>[
              Icon(
                _iconFor(call.type, call.isVideo),
                size: 15,
                color: _colorFor(call.type, secondary),
              ),
              const SizedBox(width: 4),
              Text(
                '${call.label} · ${formatTimeAgo(call.time)}',
                style: TextStyle(
                  fontSize: 13,
                  color: _colorFor(call.type, secondary),
                ),
              ),
            ],
          ),
          trailing: IconButton(
            icon: Icon(
              call.isVideo ? Icons.videocam_outlined : Icons.call_outlined,
              color: AppColors.teal,
            ),
            tooltip: 'إعادة الاتصال',
            onPressed: () {
              ScaffoldMessenger.of(context).showSnackBar(
                SnackBar(content: Text('الاتصال بـ ${call.name} — تجريبي')),
              );
            },
          ),
          onTap: () {
            ScaffoldMessenger.of(context).showSnackBar(
              SnackBar(content: Text('تفاصيل مكالمة ${call.name} — تجريبي')),
            );
          },
        );
      },
    );
  }
}
