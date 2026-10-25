/// تحديث حالة (Status) في تبويب «الحالة».
class StatusItem {
  const StatusItem({
    required this.name,
    required this.time,
    this.viewed = false,
  });

  final String name;
  final DateTime time;
  final bool viewed;
}
