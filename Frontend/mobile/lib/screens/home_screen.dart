import 'package:flutter/material.dart';
import '../widgets/chat_imput_bar.dart';

class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key});

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> {
  @override
  Widget build(BuildContext context) {
    return Scaffold(
        appBar: AppBar(title: const Text('Chat')),
        body: Column(
          children: [
            // Platzhalter für den Chat-Verlauf
            const Expanded(
              child: Center(
                child: Text('Hier kommen die Nachrichten hin'),
              ),
            ),
            // Dein neues Widget
            ChatInputBar(
              onSend: (message) {
                print("Nachricht gesendet: $message");
                // Hier später: POST Request an dein Backend
              },
            ),
          ],
        ),
    );
    }
}
