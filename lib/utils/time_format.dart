// دوال تنسيق الوقت بالعربي (بدون مكتبات خارجية).

const List<String> _arabicWeekdays = <String>[
  'الاثنين',
  'الثلاثاء',
  'الأربعاء',
  'الخميس',
  'الجمعة',
  'السبت',
  'الأحد',
];

/// الوقت بنظام 12 ساعة مع ص/م — مثال: «1:30 م».
String formatClock(DateTime time) {
  final int hour12 = time.hour % 12 == 0 ? 12 : time.hour % 12;
  final String minute = time.minute.toString().padLeft(2, '0');
  final String period = time.hour < 12 ? 'ص' : 'م';
  return '$hour12:$minute $period';
}

/// تنسيق نسبي: الوقت لو النهارده، «أمس»، اسم اليوم لو خلال الأسبوع، وإلا التاريخ.
String formatRelative(DateTime time, {DateTime? now}) {
  final DateTime reference = now ?? DateTime.now();
  final DateTime day = DateTime(time.year, time.month, time.day);
  final DateTime today = DateTime(reference.year, reference.month, reference.day);
  final int differenceInDays = today.difference(day).inDays;

  if (differenceInDays == 0) {
    return formatClock(time);
  }
  if (differenceInDays == 1) {
    return 'أمس';
  }
  if (differenceInDays > 1 && differenceInDays < 7) {
    return _arabicWeekdays[time.weekday - 1];
  }
  final String dayPart = time.day.toString().padLeft(2, '0');
  final String monthPart = time.month.toString().padLeft(2, '0');
  return '$dayPart/$monthPart/${time.year}';
}

/// «منذ 5 دقيقة» / «الآن».
String formatTimeAgo(DateTime time, {DateTime? now}) {
  final Duration difference = (now ?? DateTime.now()).difference(time);
  if (difference.inMinutes < 1) {
    return 'الآن';
  }
  if (difference.inMinutes < 60) {
    return 'منذ ${difference.inMinutes} دقيقة';
  }
  if (difference.inHours < 24) {
    return 'منذ ${difference.inHours} ساعة';
  }
  return 'منذ ${difference.inDays} يوم';
}
