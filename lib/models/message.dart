/// حالة تسليم الرسالة (نفس علامات واتساب).
enum MessageStatus { sending, sent, delivered, read }

/// رسالة واحدة داخل محادثة.
class Message {
  const Message({
    required this.id,
    required this.text,
    required this.time,
    required this.isMe,
    this.status = MessageStatus.read,
  });

  final String id;
  final String text;
  final DateTime time;

  /// true لو الرسالة من المستخدم الحالي.
  final bool isMe;
  final MessageStatus status;

  Message copyWith({MessageStatus? status}) {
    return Message(
      id: id,
      text: text,
      time: time,
      isMe: isMe,
      status: status ?? this.status,
    );
  }
}
