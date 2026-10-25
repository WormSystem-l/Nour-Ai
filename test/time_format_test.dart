import 'package:conanty/utils/time_format.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  group('formatClock', () {
    test('يحوّل لنظام 12 ساعة مع ص/م', () {
      expect(formatClock(DateTime(2026, 10, 25, 0, 5)), '12:05 ص');
      expect(formatClock(DateTime(2026, 10, 25, 9, 30)), '9:30 ص');
      expect(formatClock(DateTime(2026, 10, 25, 13, 30)), '1:30 م');
      expect(formatClock(DateTime(2026, 10, 25, 12, 0)), '12:00 م');
    });
  });

  group('formatRelative', () {
    final DateTime now = DateTime(2026, 10, 25, 15, 0);

    test('اليوم يرجع الوقت', () {
      expect(formatRelative(DateTime(2026, 10, 25, 9, 30), now: now), '9:30 ص');
    });

    test('أمس', () {
      expect(formatRelative(DateTime(2026, 10, 24, 21, 0), now: now), 'أمس');
    });

    test('اسم اليوم داخل الأسبوع', () {
      expect(formatRelative(DateTime(2026, 10, 22, 21, 0), now: now), 'الخميس');
    });

    test('تاريخ كامل للأقدم من أسبوع', () {
      expect(formatRelative(DateTime(2026, 3, 1, 10, 0), now: now), '01/03/2026');
    });
  });

  group('formatTimeAgo', () {
    final DateTime now = DateTime(2026, 10, 25, 15, 0);

    test('الآن', () {
      expect(
        formatTimeAgo(now.subtract(const Duration(seconds: 30)), now: now),
        'الآن',
      );
    });

    test('دقايق', () {
      expect(
        formatTimeAgo(now.subtract(const Duration(minutes: 20)), now: now),
        'منذ 20 دقيقة',
      );
    });

    test('ساعات', () {
      expect(
        formatTimeAgo(now.subtract(const Duration(hours: 5)), now: now),
        'منذ 5 ساعة',
      );
    });

    test('أيام', () {
      expect(
        formatTimeAgo(now.subtract(const Duration(days: 3)), now: now),
        'منذ 3 يوم',
      );
    });
  });
}
