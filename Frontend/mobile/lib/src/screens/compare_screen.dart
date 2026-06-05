import 'package:flutter/material.dart';

import '../app_state.dart';
import '../i18n/app_strings.dart';
import '../theme/app_theme.dart';

class CompareScreen extends StatefulWidget {
  const CompareScreen({super.key});

  @override
  State<CompareScreen> createState() => _CompareScreenState();
}

class _CompareScreenState extends State<CompareScreen> {
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
    final palette = ChatPalette.of(context);

    return SafeArea(
      top: false,
      child: CustomScrollView(
        slivers: [
          SliverPadding(
            padding: const EdgeInsets.fromLTRB(16, 20, 16, 12),
            sliver: SliverToBoxAdapter(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    children: [
                      Expanded(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text(
                              strings.t('compare.title'),
                              style: Theme.of(context).textTheme.headlineSmall,
                            ),
                            const SizedBox(height: 4),
                            Text(
                              strings.t('compare.subtitle'),
                              style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                                    color: palette.muted,
                                  ),
                            ),
                          ],
                        ),
                      ),
                      IconButton.filledTonal(
                        tooltip: strings.t('compare.addPanel'),
                        onPressed: app.comparePanels.length >= 4
                            ? null
                            : app.addComparePanel,
                        icon: const Icon(Icons.add),
                      ),
                      const SizedBox(width: 8),
                      IconButton(
                        tooltip: strings.t('compare.clearResults'),
                        onPressed: app.clearComparison,
                        icon: const Icon(Icons.delete_outline),
                      ),
                    ],
                  ),
                  const SizedBox(height: 16),
                  Card(
                    child: Padding(
                      padding: const EdgeInsets.all(12),
                      child: Column(
                        children: [
                          TextField(
                            controller: _promptController,
                            minLines: 3,
                            maxLines: 7,
                            decoration: InputDecoration(
                              hintText: strings.t('compare.promptPlaceholder'),
                            ),
                          ),
                          const SizedBox(height: 10),
                          Align(
                            alignment: Alignment.centerRight,
                            child: FilledButton.icon(
                              onPressed: () => _runComparison(context),
                              icon: const Icon(Icons.send_outlined),
                              label: Text(strings.t('compare.send')),
                            ),
                          ),
                        ],
                      ),
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
              final wide = constraints.crossAxisExtent >= 760;
              final columnCount = app.comparePanels.length.clamp(2, 4).toInt();
              return SliverGrid.builder(
                itemCount: app.comparePanels.length,
                gridDelegate: SliverGridDelegateWithFixedCrossAxisCount(
                    crossAxisCount: wide ? columnCount : 1,
                    crossAxisSpacing: 12,
                    mainAxisSpacing: 12,
                    childAspectRatio: wide ? 0.72 : 0.9,
                  ),
                  itemBuilder: (context, index) {
                    return _ComparePanel(
                      panel: app.comparePanels[index],
                      prompt: _promptController.text,
                    );
                  },
                );
              },
            ),
          ),
        ],
      ),
    );
  }

  void _runComparison(BuildContext context) {
    final app = AppScope.read(context);
    final strings = AppStrings.of(context);
    app.runComparison(
      prompt: _promptController.text,
      answerBuilder: (model) => strings.t(
        'mobile.compareAnswer',
        {'model': model.label},
      ),
    );
  }
}

class _ComparePanel extends StatelessWidget {
  const _ComparePanel({
    required this.panel,
    required this.prompt,
  });

  final ComparePanelData panel;
  final String prompt;

  @override
  Widget build(BuildContext context) {
    final app = AppScope.watch(context);
    final strings = AppStrings.of(context);
    final palette = ChatPalette.of(context);
    final model = app.modelById(panel.modelId);

    return Card(
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Expanded(
                  child: DropdownButtonFormField<String>(
                    initialValue: panel.modelId,
                    decoration: InputDecoration(
                      labelText: strings.t('compare.modelPlaceholder'),
                    ),
                    items: [
                      for (final item in app.sortedModels)
                        DropdownMenuItem(
                          value: item.id,
                          enabled: !item.locked,
                          child: Text(
                            item.locked
                                ? '${item.label} · ${strings.t('models.keyRequired')}'
                                : item.label,
                            overflow: TextOverflow.ellipsis,
                          ),
                        ),
                    ],
                    onChanged: (value) {
                      if (value != null) {
                        app.setComparePanelModel(panel.id, value);
                      }
                    },
                  ),
                ),
                if (app.comparePanels.length > 2)
                  IconButton(
                    tooltip: strings.t('compare.removePanel'),
                    onPressed: () => app.removeComparePanel(panel.id),
                    icon: const Icon(Icons.close),
                  ),
              ],
            ),
            const SizedBox(height: 12),
            Row(
              children: [
                Chip(label: Text(model.provider)),
                const SizedBox(width: 8),
                if (model.locked)
                  Chip(
                    avatar: const Icon(Icons.lock_outline, size: 16),
                    label: Text(strings.t('models.keyRequired')),
                  ),
              ],
            ),
            const SizedBox(height: 12),
            Expanded(
              child: Container(
                width: double.infinity,
                padding: const EdgeInsets.all(12),
                decoration: BoxDecoration(
                  borderRadius: BorderRadius.circular(10),
                  color: palette.paper,
                  border: Border.all(color: palette.hairline),
                ),
                child: panel.answer == null
                    ? Center(
                        child: Text(
                          strings.t('compare.panelEmpty'),
                          textAlign: TextAlign.center,
                          style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                                color: palette.muted,
                              ),
                        ),
                      )
                    : SingleChildScrollView(
                        child: Text(panel.answer!),
                      ),
              ),
            ),
            const SizedBox(height: 12),
            Wrap(
              spacing: 8,
              runSpacing: 8,
              children: [
                _TinyStat(
                  label: strings.t('compare.promptTokens'),
                  value: '740',
                ),
                _TinyStat(
                  label: strings.t('compare.outputTokens'),
                  value: '900',
                ),
                _TinyStat(
                  label: strings.t('compare.cost'),
                  value: r'$0.0068',
                ),
              ],
            ),
            const SizedBox(height: 12),
            Row(
              children: [
                Expanded(
                  child: OutlinedButton(
                    onPressed: panel.answer == null
                        ? null
                        : () => app.keepComparePanel(
                              panel.id,
                              strings.t('compare.title'),
                              prompt.isEmpty
                                  ? strings.t('compare.promptPlaceholder')
                                  : prompt,
                            ),
                    child: Text(
                      panel.kept
                          ? strings.t('compare.kept')
                          : strings.t('compare.keepChat'),
                    ),
                  ),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}

class _TinyStat extends StatelessWidget {
  const _TinyStat({
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
        color: palette.accentSoft,
        borderRadius: BorderRadius.circular(8),
      ),
      child: Text(
        '$label $value',
        style: TextStyle(
          color: palette.accent,
          fontWeight: FontWeight.w700,
          fontSize: 12,
        ),
      ),
    );
  }
}
