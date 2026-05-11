import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mobile/src/app.dart';

void main() {
  testWidgets('renders Chat Sribiddi shell and sends a local prompt', (
    WidgetTester tester,
  ) async {
    await tester.pumpWidget(const ChatSribiddiMobileApp());

    expect(find.text('Chat Sribiddi'), findsWidgets);
    expect(find.text('GPT-5.2'), findsWidgets);

    await tester.enterText(find.byType(TextField).last, 'Hallo Flutter');
    await tester.tap(find.byIcon(Icons.arrow_upward));
    await tester.pumpAndSettle();

    expect(find.text('Hallo Flutter'), findsWidgets);
    expect(find.textContaining('GPT-5.2'), findsWidgets);
  });

  testWidgets('opens the model catalog from mobile navigation', (
    WidgetTester tester,
  ) async {
    await tester.pumpWidget(const ChatSribiddiMobileApp());

    await tester.tap(find.byIcon(Icons.memory_outlined));
    await tester.pumpAndSettle();

    expect(find.text('Modellkatalog'), findsOneWidget);
    expect(find.text('GPT-5.2'), findsWidgets);
  });
}
