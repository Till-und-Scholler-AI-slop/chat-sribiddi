import 'package:flutter/material.dart';

import '../app_state.dart';
import '../i18n/app_strings.dart';
import '../theme/app_theme.dart';

class ChatScreen extends StatefulWidget {
  const ChatScreen({super.key});

  @override
  State<ChatScreen> createState() => _ChatScreenState();
}

class _ChatScreenState extends State<ChatScreen> {
  final _promptController = TextEditingController();

  @override
  void dispose() {
    _promptController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final app = AppScope.watch(context);
    final strings = AppStrings.of(context);
    final conversation = app.activeConversation;

    return SafeArea(
      top: false,
      child: Column(
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 12, 16, 8),
            child: _ChatHeader(conversation: conversation),
          ),
          Expanded(
            child: conversation.messages.isEmpty
                ? _WelcomePanel(
                    onPick: (prompt) {
                      _promptController.text = prompt;
                      _sendPrompt(context);
                    },
                  )
                : ListView.builder(
                    padding: const EdgeInsets.fromLTRB(16, 6, 16, 16),
                    itemCount: conversation.messages.length,
                    itemBuilder: (context, index) {
                      return _MessageBubble(
                        message: conversation.messages[index],
                      );
                    },
                  ),
          ),
          Padding(
            padding: EdgeInsets.fromLTRB(
              12,
              8,
              12,
              12 + MediaQuery.paddingOf(context).bottom,
            ),
            child: _PromptComposer(
              controller: _promptController,
              onSend: () => _sendPrompt(context),
            ),
          ),
          if (conversation.messages.isEmpty)
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 0, 16, 14),
              child: Text(
                strings.t('mobile.oauthHint'),
                textAlign: TextAlign.center,
                style: Theme.of(context).textTheme.bodySmall,
              ),
            ),
        ],
      ),
    );
  }

  void _sendPrompt(BuildContext context) {
    final app = AppScope.read(context);
    final strings = AppStrings.of(context);
    final web = strings.t(
      app.webSearchEnabled ? 'mobile.webOn' : 'mobile.webOff',
    );
    app.sendPrompt(
      prompt: _promptController.text,
      assistantReply: strings.t(
        'mobile.assistantReply',
        {
          'model': app.selectedModel.label,
          'web': web,
        },
      ),
    );
    _promptController.clear();
  }
}

class _ChatHeader extends StatelessWidget {
  const _ChatHeader({required this.conversation});

  final Conversation conversation;

  @override
  Widget build(BuildContext context) {
    final app = AppScope.watch(context);
    final strings = AppStrings.of(context);
    final palette = ChatPalette.of(context);
    final model = app.selectedModel;

    return Card(
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Row(
          children: [
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    conversation.title.isEmpty
                        ? strings.t('chat.newChatPlaceholder')
                        : conversation.title,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: Theme.of(context).textTheme.titleMedium,
                  ),
                  const SizedBox(height: 3),
                  Text(
                    '${model.provider} · ${model.label}',
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: Theme.of(context).textTheme.bodySmall,
                  ),
                ],
              ),
            ),
            IconButton.filledTonal(
              tooltip: strings.t('chat.exportRunbook'),
              onPressed: conversation.messages.isEmpty ? null : () {},
              icon: const Icon(Icons.download_outlined),
            ),
            const SizedBox(width: 8),
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 7),
              decoration: BoxDecoration(
                color: palette.accentSoft,
                borderRadius: BorderRadius.circular(999),
              ),
              child: Text(
                model.label,
                style: TextStyle(
                  color: palette.accent,
                  fontWeight: FontWeight.w700,
                  fontSize: 12,
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _WelcomePanel extends StatelessWidget {
  const _WelcomePanel({required this.onPick});

  final ValueChanged<String> onPick;

  @override
  Widget build(BuildContext context) {
    final strings = AppStrings.of(context);
    final palette = ChatPalette.of(context);
    final suggestions = [
      (
        strings.t('chat.suggestionQuantum'),
        strings.t('chat.suggestionQuantumPrompt'),
      ),
      (
        strings.t('chat.suggestionPython'),
        strings.t('chat.suggestionPythonPrompt'),
      ),
      (
        strings.t('chat.suggestionAi'),
        strings.t('chat.suggestionAiPrompt'),
      ),
    ];

    return SingleChildScrollView(
      padding: const EdgeInsets.all(24),
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          const SizedBox(height: 24),
          CircleAvatar(
            radius: 34,
            backgroundColor: palette.accentSoft,
            foregroundColor: palette.accent,
            child: const Icon(Icons.auto_awesome, size: 30),
          ),
          const SizedBox(height: 18),
          Text(
            strings.t('common.appName'),
            style: Theme.of(context).textTheme.headlineSmall,
          ),
          const SizedBox(height: 10),
          Text(
            strings.t('chat.welcomeIntro'),
            textAlign: TextAlign.center,
            style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                  color: palette.muted,
                ),
          ),
          const SizedBox(height: 20),
          Wrap(
            spacing: 8,
            runSpacing: 8,
            alignment: WrapAlignment.center,
            children: [
              for (final suggestion in suggestions)
                ActionChip(
                  label: Text(suggestion.$1),
                  avatar: const Icon(Icons.lightbulb_outline, size: 18),
                  onPressed: () => onPick(suggestion.$2),
                ),
            ],
          ),
        ],
      ),
    );
  }
}

class _MessageBubble extends StatelessWidget {
  const _MessageBubble({required this.message});

  final ChatMessage message;

  @override
  Widget build(BuildContext context) {
    final strings = AppStrings.of(context);
    final palette = ChatPalette.of(context);
    final user = message.role == MessageRole.user;

    return Align(
      alignment: user ? Alignment.centerRight : Alignment.centerLeft,
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 680),
        child: Container(
          margin: const EdgeInsets.symmetric(vertical: 6),
          padding: const EdgeInsets.all(13),
          decoration: BoxDecoration(
            color: user ? palette.userBubble : palette.assistantBubble,
            borderRadius: BorderRadius.only(
              topLeft: const Radius.circular(14),
              topRight: const Radius.circular(14),
              bottomLeft: Radius.circular(user ? 14 : 4),
              bottomRight: Radius.circular(user ? 4 : 14),
            ),
            border: Border.all(color: palette.hairline),
          ),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(message.text),
              const SizedBox(height: 8),
              Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Text(
                    _formatTime(message.createdAt),
                    style: Theme.of(context).textTheme.bodySmall,
                  ),
                  if (message.tokens != null) ...[
                    const SizedBox(width: 8),
                    Text(
                      '${message.tokens} ${strings.t('chat.tokenSuffix')}',
                      style: Theme.of(context).textTheme.bodySmall,
                    ),
                  ],
                  if (message.cost != null) ...[
                    const SizedBox(width: 8),
                    Text(
                      '\$${message.cost!.toStringAsFixed(4)}',
                      style: Theme.of(context).textTheme.bodySmall?.copyWith(
                            color: palette.accent,
                            fontWeight: FontWeight.w700,
                          ),
                    ),
                  ],
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _PromptComposer extends StatelessWidget {
  const _PromptComposer({
    required this.controller,
    required this.onSend,
  });

  final TextEditingController controller;
  final VoidCallback onSend;

  @override
  Widget build(BuildContext context) {
    final app = AppScope.watch(context);
    final strings = AppStrings.of(context);
    final palette = ChatPalette.of(context);

    return Card(
      child: Padding(
        padding: const EdgeInsets.all(10),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            SingleChildScrollView(
              scrollDirection: Axis.horizontal,
              child: Row(
                children: [
                  IconButton(
                    tooltip: strings.t('chat.addAttachments'),
                    onPressed: () {},
                    icon: const Icon(Icons.attach_file),
                  ),
                  FilledButton.tonalIcon(
                    onPressed: () => _showModelSheet(context),
                    icon: const Icon(Icons.memory, size: 18),
                    label: Text(app.selectedModel.label),
                  ),
                  const SizedBox(width: 8),
                  FilterChip(
                    selected: app.webSearchEnabled,
                    onSelected: (_) => app.toggleWebSearch(),
                    avatar: Icon(
                      app.webSearchEnabled ? Icons.public : Icons.public_off,
                      size: 18,
                    ),
                    label: Text(
                      app.webSearchEnabled
                          ? strings.t('chat.webSearchActive')
                          : strings.t('chat.webSearchInactive'),
                    ),
                  ),
                ],
              ),
            ),
            const SizedBox(height: 8),
            Row(
              crossAxisAlignment: CrossAxisAlignment.end,
              children: [
                Expanded(
                  child: TextField(
                    controller: controller,
                    minLines: 1,
                    maxLines: 5,
                    textInputAction: TextInputAction.newline,
                    decoration: InputDecoration(
                      hintText: strings.t('mobile.promptPlaceholder'),
                      prefixIcon: const Icon(Icons.chat_outlined),
                    ),
                  ),
                ),
                const SizedBox(width: 8),
                Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    if (app.showPreSendCost)
                      Padding(
                        padding: const EdgeInsets.only(bottom: 6),
                        child: Text(
                          strings.t('mobile.estimatedCost', {'amount': '\$0.01'}),
                          style: Theme.of(context).textTheme.bodySmall?.copyWith(
                                color: palette.accent,
                              ),
                        ),
                      ),
                    IconButton.filled(
                      tooltip: strings.t('chat.sendMessage'),
                      onPressed: onSend,
                      icon: const Icon(Icons.arrow_upward),
                    ),
                  ],
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }

  void _showModelSheet(BuildContext context) {
    final app = AppScope.read(context);
    final strings = AppStrings.of(context);
    showModalBottomSheet<void>(
      context: context,
      showDragHandle: true,
      builder: (context) {
        return SafeArea(
          child: ListView(
            shrinkWrap: true,
            padding: const EdgeInsets.fromLTRB(16, 0, 16, 20),
            children: [
              Text(
                strings.t('chat.modelPlaceholder'),
                style: Theme.of(context).textTheme.titleLarge,
              ),
              const SizedBox(height: 12),
              for (final model in app.sortedModels)
                ListTile(
                  leading: Icon(
                    model.locked ? Icons.lock_outline : Icons.memory_outlined,
                  ),
                  title: Text(model.label),
                  subtitle: Text('${model.provider} · ${model.inputPrice} in'),
                  trailing: model.favorite
                      ? const Icon(Icons.star, color: Colors.amber)
                      : null,
                  onTap: () {
                    Navigator.pop(context);
                    app.selectModel(model.id);
                  },
                ),
            ],
          ),
        );
      },
    );
  }
}

String _formatTime(DateTime date) {
  final hour = date.hour.toString().padLeft(2, '0');
  final minute = date.minute.toString().padLeft(2, '0');
  return '$hour:$minute';
}
