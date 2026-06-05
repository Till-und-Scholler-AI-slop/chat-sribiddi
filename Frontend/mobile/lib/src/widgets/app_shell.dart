import 'package:flutter/material.dart';

import '../app_state.dart';
import '../i18n/app_strings.dart';
import '../screens/chat_screen.dart';
import '../screens/compare_screen.dart';
import '../screens/models_screen.dart';
import '../screens/settings_screen.dart';
import '../screens/usage_screen.dart';
import '../theme/app_theme.dart';

class AppShell extends StatelessWidget {
  const AppShell({super.key});

  static const _desktopBreakpoint = 900.0;

  @override
  Widget build(BuildContext context) {
    final app = AppScope.watch(context);
    final strings = AppStrings.of(context);

    return LayoutBuilder(
      builder: (context, constraints) {
        final desktop = constraints.maxWidth >= _desktopBreakpoint;
        if (desktop) {
          return Scaffold(
            body: Row(
              children: [
                const _DesktopSidebar(),
                const VerticalDivider(width: 1),
                Expanded(child: _SectionView(section: app.section)),
              ],
            ),
          );
        }

        return Scaffold(
          appBar: AppBar(
            title: Text(_sectionLabel(strings, app.section)),
            actions: [
              if (app.section == AppSection.chat)
                IconButton(
                  tooltip: strings.t('sidebar.newChat'),
                  onPressed: () {
                    app.startNewChat(strings.t('sidebar.newChatPlaceholder'));
                  },
                  icon: const Icon(Icons.add_comment_outlined),
                ),
            ],
          ),
          body: _SectionView(section: app.section),
          bottomNavigationBar: const _MobileNavigation(),
        );
      },
    );
  }
}

class _SectionView extends StatelessWidget {
  const _SectionView({required this.section});

  final AppSection section;

  @override
  Widget build(BuildContext context) {
    return switch (section) {
      AppSection.chat => const ChatScreen(),
      AppSection.models => const ModelsScreen(),
      AppSection.compare => const CompareScreen(),
      AppSection.usage => const UsageScreen(),
      AppSection.settings => const SettingsScreen(),
    };
  }
}

class _MobileNavigation extends StatelessWidget {
  const _MobileNavigation();

  @override
  Widget build(BuildContext context) {
    final app = AppScope.watch(context);
    final strings = AppStrings.of(context);
    final sections = _mobileSections;
    final selectedIndex =
        sections.contains(app.section) ? sections.indexOf(app.section) : 0;
    return NavigationBar(
      selectedIndex: selectedIndex,
      onDestinationSelected: (index) => app.setSection(sections[index]),
      destinations: [
        for (final section in sections)
          NavigationDestination(
            icon: Icon(_sectionIcon(section)),
            selectedIcon: Icon(_sectionSelectedIcon(section)),
            label: _sectionLabel(strings, section),
          ),
      ],
    );
  }
}

class _DesktopSidebar extends StatelessWidget {
  const _DesktopSidebar();

  @override
  Widget build(BuildContext context) {
    final app = AppScope.watch(context);
    final strings = AppStrings.of(context);
    final palette = ChatPalette.of(context);

    return Container(
      width: 304,
      color: palette.elevated,
      child: SafeArea(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Padding(
              padding: const EdgeInsets.all(16),
              child: Row(
                children: [
                  CircleAvatar(
                    radius: 18,
                    backgroundColor: palette.accentSoft,
                    foregroundColor: palette.accent,
                    child: const Icon(Icons.auto_awesome, size: 19),
                  ),
                  const SizedBox(width: 10),
                  Expanded(
                    child: Text(
                      strings.t('common.appName'),
                      style: Theme.of(context).textTheme.titleMedium,
                    ),
                  ),
                  FilledButton.icon(
                    onPressed: () {
                      app.startNewChat(strings.t('sidebar.newChatPlaceholder'));
                    },
                    icon: const Icon(Icons.add, size: 18),
                    label: Text(strings.t('sidebar.newChat')),
                  ),
                ],
              ),
            ),
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 12),
              child: Text(
                strings.t('sidebar.chatsTitle'),
                style: Theme.of(context).textTheme.labelLarge?.copyWith(
                      color: palette.muted,
                    ),
              ),
            ),
            const SizedBox(height: 8),
            Expanded(
              child: ListView.separated(
                padding: const EdgeInsets.symmetric(horizontal: 8),
                itemCount: app.conversations.length,
                separatorBuilder: (context, index) => const SizedBox(height: 4),
                itemBuilder: (context, index) {
                  final conversation = app.conversations[index];
                  final active = conversation.id == app.activeConversation.id;
                  return ListTile(
                    dense: true,
                    selected: active,
                    selectedTileColor: palette.accentSoft,
                    shape: RoundedRectangleBorder(
                      borderRadius: BorderRadius.circular(8),
                    ),
                    title: Text(
                      conversation.title.isEmpty
                          ? strings.t('sidebar.newChatPlaceholder')
                          : conversation.title,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                    ),
                    subtitle: conversation.messages.isEmpty
                        ? null
                        : Text(
                            conversation.messages.last.text,
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                          ),
                    onTap: () => app.selectConversation(conversation.id),
                  );
                },
              ),
            ),
            Padding(
              padding: const EdgeInsets.all(12),
              child: _KeyBadge(),
            ),
            const Divider(),
            Padding(
              padding: const EdgeInsets.fromLTRB(8, 8, 8, 12),
              child: Column(
                children: [
                  for (final section in _desktopSections)
                    _SidebarButton(
                      section: section,
                      selected: app.section == section,
                    ),
                  const SizedBox(height: 4),
                  ListTile(
                    dense: true,
                    leading: const Icon(Icons.logout),
                    title: Text(strings.t('sidebar.logout')),
                    onTap: () => app.setSection(AppSection.settings),
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _SidebarButton extends StatelessWidget {
  const _SidebarButton({
    required this.section,
    required this.selected,
  });

  final AppSection section;
  final bool selected;

  @override
  Widget build(BuildContext context) {
    final app = AppScope.watch(context);
    final strings = AppStrings.of(context);
    final palette = ChatPalette.of(context);
    return ListTile(
      dense: true,
      leading: Icon(selected ? _sectionSelectedIcon(section) : _sectionIcon(section)),
      title: Text(_sectionLabel(strings, section)),
      selected: selected,
      selectedTileColor: palette.accentSoft,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(8)),
      onTap: () => app.setSection(section),
    );
  }
}

class _KeyBadge extends StatelessWidget {
  @override
  Widget build(BuildContext context) {
    final strings = AppStrings.of(context);
    final palette = ChatPalette.of(context);
    return InkWell(
      borderRadius: BorderRadius.circular(10),
      onTap: () => AppScope.read(context).setSection(AppSection.settings),
      child: Container(
        padding: const EdgeInsets.all(12),
        decoration: BoxDecoration(
          borderRadius: BorderRadius.circular(10),
          border: Border.all(color: palette.hairline),
          color: palette.paper,
        ),
        child: Row(
          children: [
            Icon(Icons.key_outlined, color: palette.accent),
            const SizedBox(width: 10),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    strings.t('mobile.keyAccess'),
                    style: Theme.of(context).textTheme.labelMedium?.copyWith(
                          color: palette.muted,
                        ),
                  ),
                  const SizedBox(height: 2),
                  Text(
                    strings.t('mobile.keyAccessHint'),
                    style: Theme.of(context).textTheme.bodySmall,
                  ),
                ],
              ),
            ),
            Chip(
              label: Text(strings.t('mobile.byokOnly')),
              visualDensity: VisualDensity.compact,
            ),
          ],
        ),
      ),
    );
  }
}

const _desktopSections = [
  AppSection.chat,
  AppSection.models,
  AppSection.compare,
  AppSection.usage,
  AppSection.settings,
];

const _mobileSections = [
  AppSection.chat,
  AppSection.models,
  AppSection.compare,
  AppSection.usage,
  AppSection.settings,
];

String _sectionLabel(AppStrings strings, AppSection section) {
  return switch (section) {
    AppSection.chat => strings.t('sidebar.chatsTitle'),
    AppSection.models => strings.t('sidebar.models'),
    AppSection.compare => strings.t('sidebar.compare'),
    AppSection.usage => strings.t('sidebar.usage'),
    AppSection.settings => strings.t('sidebar.settings'),
  };
}

IconData _sectionIcon(AppSection section) {
  return switch (section) {
    AppSection.chat => Icons.chat_bubble_outline,
    AppSection.models => Icons.memory_outlined,
    AppSection.compare => Icons.compare_arrows_outlined,
    AppSection.usage => Icons.bar_chart_outlined,
    AppSection.settings => Icons.settings_outlined,
  };
}

IconData _sectionSelectedIcon(AppSection section) {
  return switch (section) {
    AppSection.chat => Icons.chat_bubble,
    AppSection.models => Icons.memory,
    AppSection.compare => Icons.compare_arrows,
    AppSection.usage => Icons.bar_chart,
    AppSection.settings => Icons.settings,
  };
}
