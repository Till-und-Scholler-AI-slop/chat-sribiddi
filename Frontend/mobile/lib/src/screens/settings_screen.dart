import 'package:flutter/material.dart';

import '../app_state.dart';
import '../i18n/app_strings.dart';
import '../theme/app_theme.dart';

class SettingsScreen extends StatefulWidget {
  const SettingsScreen({super.key});

  @override
  State<SettingsScreen> createState() => _SettingsScreenState();
}

class _SettingsScreenState extends State<SettingsScreen> {
  String _tab = 'keys';

  @override
  Widget build(BuildContext context) {
    final app = AppScope.watch(context);
    final strings = AppStrings.of(context);
    final palette = ChatPalette.of(context);

    return CustomScrollView(
      slivers: [
        SliverPadding(
          padding: const EdgeInsets.fromLTRB(16, 20, 16, 12),
          sliver: SliverToBoxAdapter(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  strings.t('settings.title'),
                  style: Theme.of(context).textTheme.headlineSmall,
                ),
                const SizedBox(height: 8),
                Text(
                  strings.t('mobile.nativePreviewBody'),
                  style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                        color: palette.muted,
                      ),
                ),
                const SizedBox(height: 16),
                SingleChildScrollView(
                  scrollDirection: Axis.horizontal,
                  child: SegmentedButton<String>(
                    selected: {_tab},
                    onSelectionChanged: (value) {
                      setState(() => _tab = value.first);
                    },
                    segments: [
                      ButtonSegment(
                        value: 'keys',
                        icon: const Icon(Icons.key_outlined),
                        label: Text(strings.t('settings.tabs.keys')),
                      ),
                      ButtonSegment(
                        value: 'instructions',
                        icon: const Icon(Icons.description_outlined),
                        label: Text(strings.t('settings.tabs.instructions')),
                      ),
                      ButtonSegment(
                        value: 'interface',
                        icon: const Icon(Icons.palette_outlined),
                        label: Text(strings.t('settings.tabs.interface')),
                      ),
                      ButtonSegment(
                        value: 'account',
                        icon: const Icon(Icons.account_circle_outlined),
                        label: Text(strings.t('settings.tabs.account')),
                      ),
                    ],
                  ),
                ),
              ],
            ),
          ),
        ),
        SliverPadding(
          padding: const EdgeInsets.fromLTRB(16, 4, 16, 24),
          sliver: SliverToBoxAdapter(
            child: switch (_tab) {
              'keys' => _KeysSettings(strings: strings),
              'instructions' => _InstructionSettings(strings: strings),
              'interface' => _InterfaceSettings(app: app, strings: strings),
              _ => _AccountSettings(strings: strings),
            },
          ),
        ),
      ],
    );
  }
}

class _KeysSettings extends StatelessWidget {
  const _KeysSettings({required this.strings});

  final AppStrings strings;

  @override
  Widget build(BuildContext context) {
    final providers = [
      ('OpenAI', true),
      ('Google Gemini', false),
      ('Claude', true),
      ('OpenRouter', true),
      ('Brave Search', false),
    ];

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        _SettingsCard(
          title: strings.t('settings.modelKeysHeading'),
          subtitle: strings.t('settings.modelKeysDescription'),
          child: Column(
            children: [
              for (final provider in providers)
                _ProviderKeyRow(
                  provider: provider.$1,
                  connected: provider.$2,
                ),
            ],
          ),
        ),
      ],
    );
  }
}

class _ProviderKeyRow extends StatelessWidget {
  const _ProviderKeyRow({
    required this.provider,
    required this.connected,
  });

  final String provider;
  final bool connected;

  @override
  Widget build(BuildContext context) {
    final strings = AppStrings.of(context);
    final palette = ChatPalette.of(context);
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 8),
      child: Row(
        children: [
          CircleAvatar(
            backgroundColor: connected ? palette.accentSoft : palette.paper,
            foregroundColor: connected ? palette.accent : palette.muted,
            child: Icon(connected ? Icons.check : Icons.key_outlined),
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(provider, style: Theme.of(context).textTheme.titleMedium),
                Text(
                  connected
                      ? strings.t('mobile.connected')
                      : strings.t('mobile.missing'),
                  style: Theme.of(context).textTheme.bodySmall,
                ),
              ],
            ),
          ),
          OutlinedButton(
            onPressed: () {},
            child: Text(strings.t('settings.saveKey')),
          ),
        ],
      ),
    );
  }
}

class _InstructionSettings extends StatelessWidget {
  const _InstructionSettings({required this.strings});

  final AppStrings strings;

  @override
  Widget build(BuildContext context) {
    return _SettingsCard(
      title: strings.t('settings.customInstructionsHeading'),
      subtitle: strings.t('settings.customInstructionsPlaceholder'),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          TextField(
            minLines: 5,
            maxLines: 9,
            decoration: InputDecoration(
              hintText: strings.t('settings.customInstructionsPlaceholder'),
            ),
          ),
          const SizedBox(height: 12),
          FilledButton.icon(
            onPressed: () {},
            icon: const Icon(Icons.save_outlined),
            label: Text(strings.t('settings.customInstructionsSave')),
          ),
        ],
      ),
    );
  }
}

class _InterfaceSettings extends StatelessWidget {
  const _InterfaceSettings({
    required this.app,
    required this.strings,
  });

  final AppController app;
  final AppStrings strings;

  @override
  Widget build(BuildContext context) {
    return Column(
      children: [
        _SettingsCard(
          title: strings.t('settings.languageHeading'),
          subtitle: strings.t('settings.languageDescription'),
          child: SegmentedButton<String>(
            selected: {app.locale.languageCode},
            onSelectionChanged: (value) {
              app.setLocale(Locale(value.first));
            },
            segments: [
              ButtonSegment(
                value: 'de',
                label: Text(strings.t('language.german')),
              ),
              ButtonSegment(
                value: 'en',
                label: Text(strings.t('language.english')),
              ),
            ],
          ),
        ),
        const SizedBox(height: 12),
        _SettingsCard(
          title: strings.t('settings.colorModeHeading'),
          subtitle: strings.t('settings.colorModeDescription'),
          child: SegmentedButton<ThemeMode>(
            selected: {app.themeMode},
            onSelectionChanged: (value) {
              app.setThemeMode(value.first);
            },
            segments: [
              ButtonSegment(
                value: ThemeMode.light,
                icon: const Icon(Icons.light_mode_outlined),
                label: Text(strings.t('settings.colorModeLight')),
              ),
              ButtonSegment(
                value: ThemeMode.dark,
                icon: const Icon(Icons.dark_mode_outlined),
                label: Text(strings.t('settings.colorModeDark')),
              ),
              ButtonSegment(
                value: ThemeMode.system,
                icon: const Icon(Icons.brightness_auto_outlined),
                label: Text(strings.t('settings.colorModeSystem')),
              ),
            ],
          ),
        ),
        const SizedBox(height: 12),
        _SettingsCard(
          title: strings.t('settings.chatUiHeading'),
          subtitle: strings.t('settings.preSendCostLabel'),
          child: SwitchListTile(
            contentPadding: EdgeInsets.zero,
            value: app.showPreSendCost,
            title: Text(strings.t('settings.preSendCostLabel')),
            onChanged: app.setShowPreSendCost,
          ),
        ),
      ],
    );
  }
}

class _AccountSettings extends StatelessWidget {
  const _AccountSettings({required this.strings});

  final AppStrings strings;

  @override
  Widget build(BuildContext context) {
    final palette = ChatPalette.of(context);
    return Column(
      children: [
        _SettingsCard(
          title: strings.t('auth.loginTitle'),
          subtitle: strings.t('auth.loginCta'),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              FilledButton.icon(
                onPressed: () {},
                icon: const Icon(Icons.login),
                label: Text(strings.t('auth.loginButton')),
              ),
              const SizedBox(height: 10),
              Text(
                strings.t('mobile.oauthHint'),
                style: Theme.of(context).textTheme.bodySmall,
              ),
            ],
          ),
        ),
        const SizedBox(height: 12),
        Card(
          color: palette.dangerSoft,
          child: Padding(
            padding: const EdgeInsets.all(16),
            child: Row(
              children: [
                Icon(Icons.warning_amber_outlined, color: palette.ink),
                const SizedBox(width: 10),
                Expanded(
                  child: Text(
                    strings.t('settings.dangerZone'),
                    style: Theme.of(context).textTheme.titleMedium,
                  ),
                ),
                OutlinedButton(
                  onPressed: () {},
                  child: Text(strings.t('settings.deleteAccount')),
                ),
              ],
            ),
          ),
        ),
      ],
    );
  }
}

class _SettingsCard extends StatelessWidget {
  const _SettingsCard({
    required this.title,
    required this.subtitle,
    required this.child,
  });

  final String title;
  final String subtitle;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    final palette = ChatPalette.of(context);
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(title, style: Theme.of(context).textTheme.titleMedium),
            const SizedBox(height: 4),
            Text(
              subtitle,
              style: Theme.of(context).textTheme.bodySmall?.copyWith(
                    color: palette.muted,
                  ),
            ),
            const SizedBox(height: 14),
            child,
          ],
        ),
      ),
    );
  }
}
