import 'package:flutter/material.dart';

import '../models/message.dart';
import '../theme/app_theme.dart';
import '../utils/time_format.dart';

/// فقاعة رسالة واحدة مع الوقت وعلامة القراءة.
class MessageBubble extends StatelessWidget {
  const MessageBubble({super.key, required this.message});

  final Message message;

  @override
  Widget build(BuildContext context) {
    final bool isMe = message.isMe;
    final Color background =
        isMe ? AppColors.bubbleOut(context) : AppColors.bubbleIn(context);
    final Color textColor = AppColors.bubbleText(context);
    final Color timeColor = AppColors.secondaryText(context);

    return Align(
      alignment:
          isMe ? AlignmentDirectional.centerEnd : AlignmentDirectional.centerStart,
      child: Container(
        constraints: BoxConstraints(
          maxWidth: MediaQuery.sizeOf(context).width * 0.78,
        ),
        margin: const EdgeInsets.symmetric(vertical: 3, horizontal: 6),
        padding: const EdgeInsets.fromLTRB(10, 7, 10, 6),
        decoration: BoxDecoration(
          color: background,
          borderRadius: BorderRadiusDirectional.only(
            topStart: const Radius.circular(12),
            topEnd: const Radius.circular(12),
            bottomStart: Radius.circular(isMe ? 12 : 2),
            bottomEnd: Radius.circular(isMe ? 2 : 12),
          ),
          boxShadow: const <BoxShadow>[
            BoxShadow(
              color: Color(0x14000000),
              blurRadius: 1,
              offset: Offset(0, 1),
            ),
          ],
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.end,
          mainAxisSize: MainAxisSize.min,
          children: <Widget>[
            Text(
              message.text,
              style: TextStyle(fontSize: 15, height: 1.35, color: textColor),
            ),
            const SizedBox(height: 3),
            Row(
              mainAxisSize: MainAxisSize.min,
              children: <Widget>[
                Text(
                  formatClock(message.time),
                  style: TextStyle(fontSize: 10.5, color: timeColor),
                ),
                if (isMe) ...<Widget>[
                  const SizedBox(width: 4),
                  _StatusIcon(status: message.status),
                ],
              ],
            ),
          ],
        ),
      ),
    );
  }
}

class _StatusIcon extends StatelessWidget {
  const _StatusIcon({required this.status});

  final MessageStatus status;

  @override
  Widget build(BuildContext context) {
    switch (status) {
      case MessageStatus.sending:
        return const Icon(Icons.schedule, size: 13, color: AppColors.mutedText);
      case MessageStatus.sent:
        return const Icon(Icons.done, size: 15, color: AppColors.mutedText);
      case MessageStatus.delivered:
        return const Icon(Icons.done_all, size: 15, color: AppColors.mutedText);
      case MessageStatus.read:
        return const Icon(Icons.done_all, size: 15, color: AppColors.tickBlue);
    }
  }
}
