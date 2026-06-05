import 'package:flutter/material.dart';

import '../app_state.dart';
import '../i18n/app_strings.dart';
import '../theme/app_theme.dart';

class ModelsScreen extends StatefulWidget {
  const ModelsScreen({super.key});

  @override
  State<ModelsScreen> createState() => _ModelsScreenState();
}

class _ModelsScreenState extends State<ModelsScreen> {
  String _query = '';
  String _provider = 'ALL';

  @override
  Widget build(BuildContext context) {
    final app = AppScope.watch(context);
    final strings = AppStrings.of(context);
    final palette = ChatPalette.of(context);
    final providers = ['ALL', ...app.models.map((model) => model.provider).toSet()];
    final filtered = app.sortedModels.where((model) {
      final matchesProvider = _provider == 'ALL' || model.provider == _provider;
      final query = _query.trim().toLowerCase();
      final matchesQuery = query.isEmpty ||
          [
            model.label,
            model.provider,
            model.slug,
            strings.t(model.description),
            model.contextWindow,
            model.maxOutput,
          ].any((value) => value.toLowerCase().contains(query));
      return matchesProvider && matchesQuery;
    }).toList();

    return CustomScrollView(
      slivers: [
        SliverPadding(
          padding: const EdgeInsets.fromLTRB(16, 20, 16, 12),
          sliver: SliverToBoxAdapter(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  strings.t('models.title'),
                  style: Theme.of(context).textTheme.headlineSmall,
                ),
                const SizedBox(height: 8),
                Text(
                  '${strings.t('models.providerFilterAllShort')} ${app.models.length} · ${strings.t('mobile.desktopReady')}',
                  style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                        color: palette.muted,
                      ),
                ),
                const SizedBox(height: 18),
                TextField(
                  onChanged: (value) => setState(() => _query = value),
                  decoration: InputDecoration(
                    hintText: strings.t('models.searchPlaceholder'),
                    prefixIcon: const Icon(Icons.search),
                  ),
                ),
                const SizedBox(height: 12),
                SingleChildScrollView(
                  scrollDirection: Axis.horizontal,
                  child: Row(
                    children: [
                      for (final provider in providers)
                        Padding(
                          padding: const EdgeInsets.only(right: 8),
                          child: ChoiceChip(
                            selected: _provider == provider,
                            label: Text(
                              provider == 'ALL'
                                  ? strings.t('models.providerFilterAllShort')
                                  : provider,
                            ),
                            onSelected: (_) => setState(() => _provider = provider),
                          ),
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
          sliver: SliverLayoutBuilder(
            builder: (context, constraints) {
              final width = constraints.crossAxisExtent;
              final columns = width >= 1100 ? 3 : width >= 680 ? 2 : 1;
              return SliverGrid.builder(
                itemCount: filtered.length,
                gridDelegate: SliverGridDelegateWithFixedCrossAxisCount(
                  crossAxisCount: columns,
                  crossAxisSpacing: 12,
                  mainAxisSpacing: 12,
                  childAspectRatio: columns == 1 ? 1.12 : 1.0,
                ),
                itemBuilder: (context, index) {
                  return _ModelCard(model: filtered[index]);
                },
              );
            },
          ),
        ),
      ],
    );
  }
}

class _ModelCard extends StatelessWidget {
  const _ModelCard({required this.model});

  final AiModel model;

  @override
  Widget build(BuildContext context) {
    final app = AppScope.watch(context);
    final strings = AppStrings.of(context);
    final palette = ChatPalette.of(context);

    return Card(
      child: InkWell(
        borderRadius: BorderRadius.circular(10),
        onTap: () => app.selectModel(model.id),
        child: Padding(
          padding: const EdgeInsets.all(14),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  CircleAvatar(
                    backgroundColor:
                        model.locked ? palette.dangerSoft : palette.accentSoft,
                    foregroundColor:
                        model.locked ? Theme.of(context).colorScheme.error : palette.accent,
                    child: Text(
                      model.provider.isEmpty ? '?' : model.provider[0],
                      style: const TextStyle(fontWeight: FontWeight.w800),
                    ),
                  ),
                  const SizedBox(width: 10),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Row(
                          children: [
                            Expanded(
                              child: Text(
                                model.label,
                                maxLines: 1,
                                overflow: TextOverflow.ellipsis,
                                style: Theme.of(context).textTheme.titleMedium,
                              ),
                            ),
                            if (model.locked)
                              Tooltip(
                                message: strings.t('models.keyRequired'),
                                child: const Icon(Icons.lock_outline, size: 18),
                              ),
                          ],
                        ),
                        const SizedBox(height: 2),
                        Text(
                          model.slug,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: Theme.of(context).textTheme.bodySmall,
                        ),
                      ],
                    ),
                  ),
                  IconButton(
                    tooltip: model.favorite
                        ? strings.t('models.removeFromFavorites')
                        : strings.t('models.addToFavorites'),
                    onPressed: () => app.toggleFavorite(model.id),
                    icon: Icon(
                      model.favorite ? Icons.star : Icons.star_border,
                      color: model.favorite ? Colors.amber : null,
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 12),
              Text(
                strings.t(model.description),
                maxLines: 2,
                overflow: TextOverflow.ellipsis,
                style: Theme.of(context).textTheme.bodyMedium,
              ),
              const Spacer(),
              Divider(color: palette.hairline),
              const SizedBox(height: 8),
              Wrap(
                runSpacing: 8,
                spacing: 8,
                children: [
                  _MetricChip(
                    label: strings.t('models.inputLabel'),
                    value: '${model.inputPrice} ${strings.t('models.perMillionTokens')}',
                  ),
                  _MetricChip(
                    label: strings.t('models.outputLabel'),
                    value: '${model.outputPrice} ${strings.t('models.perMillionTokens')}',
                  ),
                  _MetricChip(
                    label: strings.t('mobile.context'),
                    value: model.contextWindow,
                  ),
                  _MetricChip(
                    label: strings.t('mobile.output'),
                    value: model.maxOutput,
                  ),
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _MetricChip extends StatelessWidget {
  const _MetricChip({
    required this.label,
    required this.value,
  });

  final String label;
  final String value;

  @override
  Widget build(BuildContext context) {
    final palette = ChatPalette.of(context);
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 6),
      decoration: BoxDecoration(
        color: palette.paper,
        borderRadius: BorderRadius.circular(8),
        border: Border.all(color: palette.hairline),
      ),
      child: RichText(
        text: TextSpan(
          style: Theme.of(context).textTheme.bodySmall,
          children: [
            TextSpan(text: '$label: '),
            TextSpan(
              text: value,
              style: TextStyle(
                color: palette.ink,
                fontWeight: FontWeight.w700,
              ),
            ),
          ],
        ),
      ),
    );
  }
}
