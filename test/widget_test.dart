import 'package:conanty/app.dart';
import 'package:conanty/screens/chat_screen.dart';
import 'package:conanty/widgets/chat_tile.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  testWidgets('التطبيق يفتح ويعرض المحادثات التجريبية',
      (WidgetTester tester) async {
    await tester.pumpWidget(const ConantyApp());
    await tester.pumpAndSettle();

    expect(find.text('Conanty'), findsOneWidget);
    expect(find.byType(ChatTile), findsWidgets);
    expect(
      find.descendant(
        of: find.byType(ChatTile),
        matching: find.text('منى عبد الرحمن'),
      ),
      findsOneWidget,
    );
  });

  testWidgets('فتح محادثة وإرسال رسالة مع الرد التلقائي',
      (WidgetTester tester) async {
    await tester.pumpWidget(const ConantyApp());
    await tester.pumpAndSettle();

    await tester.tap(
      find.descendant(
        of: find.byType(ChatTile),
        matching: find.text('منى عبد الرحمن'),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.byType(ChatScreen), findsOneWidget);

    await tester.enterText(find.byType(TextField), 'رسالة تجريبية');
    await tester.pump();
    await tester.tap(find.byIcon(Icons.send));
    await tester.pump();

    expect(find.text('رسالة تجريبية'), findsOneWidget);

    // الرد التلقائي مجدول بعد 900ms — لازم نستناه
    // عشان الاختبار ميسيبش مؤقت معلّق.
    await tester.pump(const Duration(seconds: 2));
    await tester.pumpAndSettle();

    expect(find.text('وصلت رسالتك ✅ (رد تلقائي تجريبي)'), findsOneWidget);
  });

  testWidgets('البحث في المحادثات يفلتر النتائج', (WidgetTester tester) async {
    await tester.pumpWidget(const ConantyApp());
    await tester.pumpAndSettle();

    await tester.enterText(find.byType(TextField), 'سارة');
    await tester.pumpAndSettle();

    expect(
      find.descendant(
        of: find.byType(ChatTile),
        matching: find.text('سارة فؤاد'),
      ),
      findsOneWidget,
    );
    expect(
      find.descendant(
        of: find.byType(ChatTile),
        matching: find.text('بابا'),
      ),
      findsNothing,
    );
  });
}
