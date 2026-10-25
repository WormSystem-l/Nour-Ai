import '../models/call_entry.dart';
import '../models/chat.dart';
import '../models/message.dart';
import '../models/status_item.dart';

/// بيانات تجريبية فقط — مفيش سيرفر ولا حسابات.
/// كل النصوص هنا تقدر تغيّرها من غير ما تلمس منطق التطبيق.

DateTime _minutesAgo(int minutes) =>
    DateTime.now().subtract(Duration(minutes: minutes));

DateTime _daysAgo(int days, {int hour = 20, int minute = 0}) {
  final DateTime now = DateTime.now();
  final DateTime day =
      DateTime(now.year, now.month, now.day).subtract(Duration(days: days));
  return DateTime(day.year, day.month, day.day, hour, minute);
}

Message _message(
  String id,
  String text,
  DateTime time, {
  bool isMe = false,
  MessageStatus status = MessageStatus.read,
}) {
  return Message(id: id, text: text, time: time, isMe: isMe, status: status);
}

List<Chat> buildDemoChats() {
  return <Chat>[
    Chat(
      id: 'c1',
      name: 'منى عبد الرحمن',
      isPinned: true,
      isOnline: true,
      unreadCount: 2,
      messages: <Message>[
        _message('c1m1', 'إزيك يا حبيبي، عملت إيه في الشغل النهارده؟',
            _minutesAgo(45)),
        _message('c1m2', 'الحمد لله خلّصت التقرير أخيرًا 😅', _minutesAgo(43),
            isMe: true),
        _message('c1m3', 'أنا قلتلك هتخلّصه! يلا نتعشى برة', _minutesAgo(40)),
        _message('c1m4', 'مستنياك 🙈', _minutesAgo(39)),
      ],
    ),
    Chat(
      id: 'c2',
      name: 'أحمد شريف',
      unreadCount: 0,
      messages: <Message>[
        _message('c2m1', 'النسخة الجديدة من الشاشة الرئيسية جاهزة على Figma',
            _minutesAgo(300), isMe: true),
        _message('c2m2', 'شفتها، شكلها تحفة 👌 بس الأيقونات محتاجة تكبر شوية',
            _minutesAgo(290)),
        _message('c2m3', 'ماشي هعدّلها وأرفعها الصبح', _minutesAgo(285),
            isMe: true),
      ],
    ),
    Chat(
      id: 'c3',
      name: 'بابا',
      isMuted: true,
      messages: <Message>[
        _message('c3m1', 'ابني إزيك، مطمّن عليك', _daysAgo(1, hour: 21, minute: 10)),
        _message('c3m2', 'تمام يا بابا، إنت عامل إيه؟', _daysAgo(1, hour: 21, minute: 30),
            isMe: true),
        _message('c3m3', 'كله تمام، متنساش تيجي الجمعة',
            _daysAgo(1, hour: 21, minute: 32)),
      ],
    ),
    Chat(
      id: 'c4',
      name: 'نادي المقاولون',
      unreadCount: 5,
      messages: <Message>[
        _message('c4m1', 'تمرين السبت اتأجل للساعة 6 مساءً. برجاء التأكيد.',
            _daysAgo(1, hour: 18, minute: 5)),
      ],
    ),
    Chat(
      id: 'c5',
      name: 'خدمة عملاء — أورنج',
      messages: <Message>[
        _message('c5m1',
            'عرض جديد: 20 جيجا إضافية بـ 50 جنيه لمدة أسبوع. للاشتراك أرسل 1',
            _daysAgo(2, hour: 12, minute: 0)),
      ],
    ),
    Chat(
      id: 'c6',
      name: 'سارة فؤاد',
      unreadCount: 1,
      messages: <Message>[
        _message('c6m1', 'ممكن تبعتلي ملف العرض بتاع امبارح؟',
            _daysAgo(3, hour: 16, minute: 45)),
      ],
    ),
    Chat(
      id: 'c7',
      name: 'محمود الجارحي',
      messages: <Message>[
        _message('c7m1', 'تمام، نتقابل بكرة الساعة 10', _daysAgo(5, hour: 11, minute: 20),
            isMe: true),
      ],
    ),
  ];
}

List<StatusItem> buildDemoStatuses() {
  return <StatusItem>[
    StatusItem(name: 'منى عبد الرحمن', time: _minutesAgo(20)),
    StatusItem(name: 'أحمد شريف', time: _minutesAgo(120), viewed: true),
    StatusItem(name: 'سارة فؤاد', time: _minutesAgo(300), viewed: true),
    StatusItem(name: 'محمود الجارحي', time: _daysAgo(1, hour: 22), viewed: true),
  ];
}

List<CallEntry> buildDemoCalls() {
  return <CallEntry>[
    CallEntry(name: 'منى عبد الرحمن', time: _minutesAgo(25), type: CallType.outgoing, isVideo: true),
    CallEntry(name: 'بابا', time: _minutesAgo(180), type: CallType.incoming),
    CallEntry(name: 'أحمد شريف', time: _daysAgo(1, hour: 19, minute: 40), type: CallType.missed),
    CallEntry(name: 'سارة فؤاد', time: _daysAgo(1, hour: 13, minute: 5), type: CallType.outgoing),
    CallEntry(name: 'نادي المقاولون', time: _daysAgo(3, hour: 10, minute: 15), type: CallType.incoming),
  ];
}
