import 'message.dart';

/// محادثة (فردية) مع قائمة رسائل مرتبة زمنيًا من الأقدم للأحدث.
class Chat {
  Chat({
    required this.id,
    required this.name,
    required this.messages,
    this.isPinned = false,
    this.isMuted = false,
    this.isOnline = false,
    this.unreadCount = 0,
  });

  final String id;
  final String name;
  final List<Message> messages;
  final bool isPinned;
  final bool isMuted;
  final bool isOnline;

  /// عدد الرسائل غير المقروءة (قابل للتغيير لأن فتح المحادثة يصفّره).
  int unreadCount;

  Message? get lastMessage => messages.isEmpty ? null : messages.last;

  DateTime get lastActivity =>
      lastMessage?.time ?? DateTime.fromMillisecondsSinceEpoch(0);

  /// النص المعروض تحت اسم المحادثة في القائمة.
  String get preview {
    final Message? last = lastMessage;
    if (last == null) {
      return 'ابدأ المحادثة';
    }
    return last.isMe ? 'أنت: ${last.text}' : last.text;
  }
}
