/// نوع المكالمة كما يظهر في تبويب «المكالمات».
enum CallType { incoming, outgoing, missed }

/// سجل مكالمة واحدة.
class CallEntry {
  const CallEntry({
    required this.name,
    required this.time,
    required this.type,
    this.isVideo = false,
  });

  final String name;
  final DateTime time;
  final CallType type;
  final bool isVideo;

  String get label {
    switch (type) {
      case CallType.incoming:
        return 'واردة';
      case CallType.outgoing:
        return 'صادرة';
      case CallType.missed:
        return 'فائتة';
    }
  }
}
