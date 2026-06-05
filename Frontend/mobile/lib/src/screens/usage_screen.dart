import 'package:flutter/material.dart';

import '../app_state.dart';
import '../i18n/app_strings.dart';
import '../theme/app_theme.dart';

class UsageScreen extends StatelessWidget {
  const UsageScreen({super.key});

  @override
  Widget build(BuildContext context) {
    final strings = AppStrings.of(context);
    final palette = ChatPalette.of(context);
    final points = _usagePoints(strings.isGerman);
    final maxTokens = points.map((point) => point.tokens).reduce((a, b) => a > b ? a : b);

    return CustomScrollView(
      slivers: [
        SliverPadding(
          padding: const EdgeInsets.fromLTRB(16, 20, 16, 12),
          sliver: SliverToBoxAdapter(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  strings.t('usage.title'),
                  style: Theme.of(context).textTheme.headlineSmall,
                ),
                const SizedBox(height: 8),
                Text(
                  strings.t('usage.subtitle'),
                  style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                        color: palette.muted,
                      ),
                ),
                const SizedBox(height: 16),
                Wrap(
                  spacing: 8,
                  children: [
                    ChoiceChip(selected: true, label: Text(strings.t('usage.range7'))),
                    ChoiceChip(selected: false, label: Text(strings.t('usage.range30'))),
                    ChoiceChip(selected: false, label: Text(strings.t('usage.range90'))),
                  ],
                ),
              ],
            ),
          ),
        ),
        SliverPadding(
          padding: const EdgeInsets.fromLTRB(16, 4, 16, 12),
          sliver: SliverLayoutBuilder(
            builder: (context, constraints) {
              final columns = constraints.crossAxisExtent >= 760 ? 3 : 1;
              return SliverGrid.count(
                crossAxisCount: columns,
                crossAxisSpacing: 12,
                mainAxisSpacing: 12,
                childAspectRatio: columns == 1 ? 3.4 : 2.1,
                children: [
                  _MetricCard(
                    icon: Icons.token_outlined,
                    label: strings.t('usage.totalTokens'),
                    value: '48.2k',
                  ),
                  _MetricCard(
                    icon: Icons.payments_outlined,
                    label: strings.t('usage.estimatedCost'),
                    value: r'$0.42',
                  ),
                  _MetricCard(
                    icon: Icons.memory_outlined,
                    label: strings.t('usage.topModel'),
                    value: 'GPT-5.2',
                  ),
                ],
              );
            },
          ),
        ),
        SliverPadding(
          padding: const EdgeInsets.fromLTRB(16, 4, 16, 12),
          sliver: SliverToBoxAdapter(
            child: Card(
              child: Padding(
                padding: const EdgeInsets.all(16),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      strings.t('usage.dailyTrend'),
                      style: Theme.of(context).textTheme.titleMedium,
                    ),
                    const SizedBox(height: 18),
                    SizedBox(
                      height: 190,
                      child: Row(
                        crossAxisAlignment: CrossAxisAlignment.end,
                        children: [
                          for (final point in points)
                            Expanded(
                              child: Padding(
                                padding: const EdgeInsets.symmetric(horizontal: 4),
                                child: _UsageBar(
                                  point: point,
                                  maxTokens: maxTokens,
                                ),
                              ),
                            ),
                        ],
                      ),
                    ),
                  ],
                ),
              ),
            ),
          ),
        ),
        SliverPadding(
          padding: const EdgeInsets.fromLTRB(16, 4, 16, 24),
          sliver: SliverToBoxAdapter(
            child: Card(
              child: Padding(
                padding: const EdgeInsets.all(16),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      children: [
                        Icon(Icons.key_outlined, color: palette.accent),
                        const SizedBox(width: 10),
                        Expanded(
                          child: Text(
                            strings.t('mobile.keyAccess'),
                            style: Theme.of(context).textTheme.titleMedium,
                          ),
                        ),
                        Chip(label: Text(strings.t('mobile.byokOnly'))),
                      ],
                    ),
                    const SizedBox(height: 12),
                    Text(
                      strings.t('mobile.usageKeyHint'),
                      style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                            color: palette.muted,
                          ),
                    ),
                    const SizedBox(height: 12),
                    FilledButton.icon(
                      onPressed: () => AppScope.read(context).setSection(AppSection.settings),
                      icon: const Icon(Icons.settings_outlined),
                      label: Text(strings.t('mobile.openSettings')),
                    ),
                  ],
                ),
              ),
            ),
          ),
        ),
      ],
    );
  }
}

class _MetricCard extends StatelessWidget {
  const _MetricCard({
    required this.icon,
    required this.label,
    required this.value,
  });

  final IconData icon;
  final String label;
  final String value;

  @override
  Widget build(BuildContext context) {
    final palette = ChatPalette.of(context);
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Row(
          children: [
            CircleAvatar(
              backgroundColor: palette.accentSoft,
              foregroundColor: palette.accent,
              child: Icon(icon),
            ),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                mainAxisAlignment: MainAxisAlignment.center,
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(label, style: Theme.of(context).textTheme.bodySmall),
                  const SizedBox(height: 4),
                  Text(
                    value,
                    style: Theme.of(context).textTheme.titleLarge,
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

class _UsageBar extends StatelessWidget {
  const _UsageBar({
    required this.point,
    required this.maxTokens,
  });

  final UsagePoint point;
  final int maxTokens;

  @override
  Widget build(BuildContext context) {
    final palette = ChatPalette.of(context);
    final heightFactor = point.tokens / maxTokens;
    return Column(
      mainAxisAlignment: MainAxisAlignment.end,
      children: [
        Expanded(
          child: Align(
            alignment: Alignment.bottomCenter,
            child: FractionallySizedBox(
              heightFactor: heightFactor.clamp(0.12, 1.0).toDouble(),
              child: Container(
                decoration: BoxDecoration(
                  color: palette.accent,
                  borderRadius: BorderRadius.circular(8),
                ),
              ),
            ),
          ),
        ),
        const SizedBox(height: 8),
        Text(
          point.label,
          style: Theme.of(context).textTheme.bodySmall,
        ),
      ],
    );
  }
}

List<UsagePoint> _usagePoints(bool german) {
  final labels = german
      ? ['Mo', 'Di', 'Mi', 'Do', 'Fr', 'Sa', 'So']
      : ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];
  return [
    UsagePoint(label: labels[0], requests: 8, tokens: 5200, cost: 0.04),
    UsagePoint(label: labels[1], requests: 11, tokens: 6800, cost: 0.06),
    UsagePoint(label: labels[2], requests: 14, tokens: 9100, cost: 0.08),
    UsagePoint(label: labels[3], requests: 7, tokens: 4300, cost: 0.03),
    UsagePoint(label: labels[4], requests: 15, tokens: 11200, cost: 0.11),
    UsagePoint(label: labels[5], requests: 4, tokens: 2100, cost: 0.01),
    UsagePoint(label: labels[6], requests: 9, tokens: 9500, cost: 0.09),
  ];
}
