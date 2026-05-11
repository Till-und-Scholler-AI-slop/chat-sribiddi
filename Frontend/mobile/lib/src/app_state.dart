import 'package:flutter/material.dart';

enum AppSection { chat, models, compare, usage, pricing, settings }

enum MessageRole { user, assistant }

class ChatMessage {
  const ChatMessage({
    required this.role,
    required this.text,
    required this.createdAt,
    this.tokens,
    this.cost,
  });

  final MessageRole role;
  final String text;
  final DateTime createdAt;
  final int? tokens;
  final double? cost;
}

class Conversation {
  Conversation({
    required this.id,
    required this.title,
    required this.messages,
  });

  final String id;
  String title;
  final List<ChatMessage> messages;
}

class AiModel {
  const AiModel({
    required this.id,
    required this.label,
    required this.provider,
    required this.slug,
    required this.description,
    required this.inputPrice,
    required this.outputPrice,
    required this.contextWindow,
    required this.maxOutput,
    this.locked = false,
    this.favorite = false,
  });

  final String id;
  final String label;
  final String provider;
  final String slug;
  final String description;
  final String inputPrice;
  final String outputPrice;
  final String contextWindow;
  final String maxOutput;
  final bool locked;
  final bool favorite;

  AiModel copyWith({
    bool? favorite,
  }) {
    return AiModel(
      id: id,
      label: label,
      provider: provider,
      slug: slug,
      description: description,
      inputPrice: inputPrice,
      outputPrice: outputPrice,
      contextWindow: contextWindow,
      maxOutput: maxOutput,
      locked: locked,
      favorite: favorite ?? this.favorite,
    );
  }
}

class ComparePanelData {
  ComparePanelData({
    required this.id,
    required this.modelId,
    this.answer,
    this.kept = false,
  });

  final String id;
  String modelId;
  String? answer;
  bool kept;
}

class UsagePoint {
  const UsagePoint({
    required this.label,
    required this.requests,
    required this.tokens,
    required this.cost,
  });

  final String label;
  final int requests;
  final int tokens;
  final double cost;
}

class AppController extends ChangeNotifier {
  AppController()
      : _activeConversationId = _initialConversations.first.id,
        _selectedModelId = _initialModels.first.id,
        _models = List<AiModel>.of(_initialModels),
        _conversations = List<Conversation>.of(_initialConversations),
        _comparePanels = [
          ComparePanelData(id: 'panel-1', modelId: _initialModels[0].id),
          ComparePanelData(id: 'panel-2', modelId: _initialModels[1].id),
        ];

  Locale _locale = const Locale('de');
  ThemeMode _themeMode = ThemeMode.system;
  AppSection _section = AppSection.chat;
  String _activeConversationId;
  String _selectedModelId;
  bool _webSearchEnabled = false;
  String _billingMode = 'AUTO';
  bool _showPreSendCost = true;
  final List<AiModel> _models;
  final List<Conversation> _conversations;
  final List<ComparePanelData> _comparePanels;

  Locale get locale => _locale;
  ThemeMode get themeMode => _themeMode;
  AppSection get section => _section;
  List<AiModel> get models => List.unmodifiable(_models);
  List<Conversation> get conversations => List.unmodifiable(_conversations);
  List<ComparePanelData> get comparePanels => List.unmodifiable(_comparePanels);
  bool get webSearchEnabled => _webSearchEnabled;
  String get billingMode => _billingMode;
  bool get showPreSendCost => _showPreSendCost;

  Conversation get activeConversation => _conversations.firstWhere(
        (conversation) => conversation.id == _activeConversationId,
      );

  AiModel get selectedModel => _models.firstWhere(
        (model) => model.id == _selectedModelId,
      );

  List<AiModel> get sortedModels {
    final copy = List<AiModel>.of(_models);
    copy.sort((a, b) {
      if (a.favorite != b.favorite) return a.favorite ? -1 : 1;
      if (a.locked != b.locked) return a.locked ? 1 : -1;
      return a.label.compareTo(b.label);
    });
    return copy;
  }

  AiModel modelById(String id) {
    return _models.firstWhere((model) => model.id == id);
  }

  void setLocale(Locale locale) {
    if (_locale.languageCode == locale.languageCode) return;
    _locale = locale;
    notifyListeners();
  }

  void setThemeMode(ThemeMode mode) {
    if (_themeMode == mode) return;
    _themeMode = mode;
    notifyListeners();
  }

  void setSection(AppSection section) {
    if (_section == section) return;
    _section = section;
    notifyListeners();
  }

  void selectConversation(String id) {
    _activeConversationId = id;
    _section = AppSection.chat;
    notifyListeners();
  }

  void startNewChat(String title) {
    final id = 'chat-${_conversations.length + 1}';
    _conversations.insert(
      0,
      Conversation(id: id, title: title, messages: []),
    );
    _activeConversationId = id;
    _section = AppSection.chat;
    notifyListeners();
  }

  void renameActiveConversation(String title) {
    activeConversation.title = title;
    notifyListeners();
  }

  void selectModel(String modelId) {
    final model = modelById(modelId);
    if (model.locked) {
      _section = AppSection.pricing;
    } else {
      _selectedModelId = modelId;
      _section = AppSection.chat;
    }
    notifyListeners();
  }

  void toggleFavorite(String modelId) {
    final index = _models.indexWhere((model) => model.id == modelId);
    if (index == -1) return;
    final model = _models[index];
    _models[index] = model.copyWith(favorite: !model.favorite);
    notifyListeners();
  }

  void toggleWebSearch() {
    _webSearchEnabled = !_webSearchEnabled;
    notifyListeners();
  }

  void sendPrompt({
    required String prompt,
    required String assistantReply,
  }) {
    final trimmed = prompt.trim();
    if (trimmed.isEmpty) return;
    final now = DateTime.now();
    final model = selectedModel;
    activeConversation.messages
      ..add(ChatMessage(role: MessageRole.user, text: trimmed, createdAt: now))
      ..add(
        ChatMessage(
          role: MessageRole.assistant,
          text: assistantReply,
          createdAt: now.add(const Duration(seconds: 1)),
          tokens: 1380 + trimmed.length * 3,
          cost: model.locked ? null : 0.0042 + trimmed.length * 0.00003,
        ),
      );
    if (activeConversation.messages.length == 2) {
      activeConversation.title = trimmed.length > 34
          ? '${trimmed.substring(0, 34).trim()}...'
          : trimmed;
    }
    notifyListeners();
  }

  void setComparePanelModel(String panelId, String modelId) {
    final model = modelById(modelId);
    if (model.locked) {
      _section = AppSection.pricing;
      notifyListeners();
      return;
    }
    final panel = _comparePanels.firstWhere((item) => item.id == panelId);
    panel.modelId = modelId;
    notifyListeners();
  }

  void addComparePanel() {
    if (_comparePanels.length >= 4) return;
    _comparePanels.add(
      ComparePanelData(
        id: 'panel-${_comparePanels.length + 1}',
        modelId: _selectedModelId,
      ),
    );
    notifyListeners();
  }

  void removeComparePanel(String panelId) {
    if (_comparePanels.length <= 2) return;
    _comparePanels.removeWhere((panel) => panel.id == panelId);
    notifyListeners();
  }

  void runComparison({
    required String prompt,
    required String Function(AiModel model) answerBuilder,
  }) {
    if (prompt.trim().isEmpty) return;
    for (final panel in _comparePanels) {
      panel.answer = answerBuilder(modelById(panel.modelId));
      panel.kept = false;
    }
    notifyListeners();
  }

  void clearComparison() {
    for (final panel in _comparePanels) {
      panel.answer = null;
      panel.kept = false;
    }
    notifyListeners();
  }

  void keepComparePanel(String panelId, String title, String prompt) {
    final panel = _comparePanels.firstWhere((item) => item.id == panelId);
    final answer = panel.answer;
    if (answer == null) return;
    panel.kept = true;
    final id = 'chat-${_conversations.length + 1}';
    _conversations.insert(
      0,
      Conversation(
        id: id,
        title: title,
        messages: [
          ChatMessage(
            role: MessageRole.user,
            text: prompt,
            createdAt: DateTime.now(),
          ),
          ChatMessage(
            role: MessageRole.assistant,
            text: answer,
            createdAt: DateTime.now().add(const Duration(seconds: 1)),
            tokens: 1640,
            cost: 0.0068,
          ),
        ],
      ),
    );
    _activeConversationId = id;
    _section = AppSection.chat;
    notifyListeners();
  }

  void setBillingMode(String mode) {
    _billingMode = mode;
    notifyListeners();
  }

  void setShowPreSendCost(bool value) {
    _showPreSendCost = value;
    notifyListeners();
  }
}

class AppScope extends InheritedNotifier<AppController> {
  const AppScope({
    super.key,
    required AppController controller,
    required super.child,
  }) : super(notifier: controller);

  static AppController watch(BuildContext context) {
    final scope = context.dependOnInheritedWidgetOfExactType<AppScope>();
    assert(scope != null, 'AppScope is missing from the widget tree.');
    return scope!.notifier!;
  }

  static AppController read(BuildContext context) {
    final element = context.getElementForInheritedWidgetOfExactType<AppScope>();
    final scope = element?.widget as AppScope?;
    assert(scope != null, 'AppScope is missing from the widget tree.');
    return scope!.notifier!;
  }
}

final _initialModels = <AiModel>[
  const AiModel(
    id: 'gpt-5.2',
    label: 'GPT-5.2',
    provider: 'OpenAI',
    slug: 'openai/gpt-5.2',
    description: 'mobile.model.gpt52.description',
    inputPrice: r'$1.25',
    outputPrice: r'$10.00',
    contextWindow: '400k',
    maxOutput: '128k',
    favorite: true,
  ),
  const AiModel(
    id: 'gemini-3-pro',
    label: 'Gemini 3 Pro',
    provider: 'Google',
    slug: 'google/gemini-3-pro',
    description: 'mobile.model.gemini3.description',
    inputPrice: r'$1.00',
    outputPrice: r'$8.00',
    contextWindow: '1M',
    maxOutput: '64k',
  ),
  const AiModel(
    id: 'claude-sonnet-4.5',
    label: 'Claude Sonnet 4.5',
    provider: 'Claude',
    slug: 'claude/sonnet-4.5',
    description: 'mobile.model.sonnet45.description',
    inputPrice: r'$3.00',
    outputPrice: r'$15.00',
    contextWindow: '200k',
    maxOutput: '64k',
    favorite: true,
  ),
  const AiModel(
    id: 'openrouter-free',
    label: 'OpenRouter Free',
    provider: 'OpenRouter',
    slug: 'openrouter/auto:free',
    description: 'mobile.model.openrouterFree.description',
    inputPrice: r'$0.00',
    outputPrice: r'$0.00',
    contextWindow: '32k',
    maxOutput: '8k',
  ),
  const AiModel(
    id: 'o3-pro',
    label: 'o3 Pro',
    provider: 'OpenAI',
    slug: 'openai/o3-pro',
    description: 'mobile.model.o3pro.description',
    inputPrice: r'$15.00',
    outputPrice: r'$60.00',
    contextWindow: '200k',
    maxOutput: '100k',
    locked: true,
  ),
  const AiModel(
    id: 'claude-opus-4.5',
    label: 'Claude Opus 4.5',
    provider: 'Claude',
    slug: 'claude/opus-4.5',
    description: 'mobile.model.opus45.description',
    inputPrice: r'$15.00',
    outputPrice: r'$75.00',
    contextWindow: '200k',
    maxOutput: '64k',
    locked: true,
  ),
];

final _initialConversations = <Conversation>[
  Conversation(
    id: 'chat-1',
    title: '',
    messages: [],
  ),
];
