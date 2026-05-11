import 'package:flutter/material.dart';

import '../i18n/app_strings.dart';
import '../theme/app_theme.dart';

class PricingScreen extends StatelessWidget {
  const PricingScreen({super.key});

  @override
  Widget build(BuildContext context) {
    final strings = AppStrings.of(context);
    final palette = ChatPalette.of(context);
    final plans = _plans(strings);

    return CustomScrollView(
      slivers: [
        SliverPadding(
          padding: const EdgeInsets.fromLTRB(16, 20, 16, 12),
          sliver: SliverToBoxAdapter(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  strings.t('pricing.title'),
                  style: Theme.of(context).textTheme.headlineSmall,
                ),
                const SizedBox(height: 8),
                Text(
                  strings.t('pricing.subtitle'),
                  style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                        color: palette.muted,
                      ),
                ),
              ],
            ),
          ),
        ),
        SliverPadding(
          padding: const EdgeInsets.fromLTRB(16, 4, 16, 16),
          sliver: SliverLayoutBuilder(
            builder: (context, constraints) {
              final columns = constraints.crossAxisExtent >= 980
                  ? 3
                  : constraints.crossAxisExtent >= 660
                      ? 2
                      : 1;
              return SliverGrid.builder(
                itemCount: plans.length,
                gridDelegate: SliverGridDelegateWithFixedCrossAxisCount(
                  crossAxisCount: columns,
                  crossAxisSpacing: 12,
                  mainAxisSpacing: 12,
                  childAspectRatio: columns == 1 ? 0.88 : 0.76,
                ),
                itemBuilder: (context, index) => _PlanCard(plan: plans[index]),
              );
            },
          ),
        ),
        SliverPadding(
          padding: const EdgeInsets.fromLTRB(16, 0, 16, 24),
          sliver: SliverToBoxAdapter(
            child: Text(
              strings.t('mobile.checkoutHint'),
              style: Theme.of(context).textTheme.bodySmall,
            ),
          ),
        ),
      ],
    );
  }
}

class _PlanCard extends StatelessWidget {
  const _PlanCard({required this.plan});

  final _PlanDefinition plan;

  @override
  Widget build(BuildContext context) {
    final strings = AppStrings.of(context);
    final palette = ChatPalette.of(context);

    return Card(
      child: Container(
        decoration: BoxDecoration(
          borderRadius: BorderRadius.circular(10),
          border: plan.highlighted
              ? Border.all(color: palette.accent, width: 2)
              : null,
        ),
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Expanded(
                  child: Text(
                    plan.name,
                    style: Theme.of(context).textTheme.titleLarge,
                  ),
                ),
                if (plan.highlighted)
                  Chip(
                    label: Text(strings.t('pricing.recommended')),
                    visualDensity: VisualDensity.compact,
                  )
                else if (plan.current)
                  Chip(
                    label: Text(strings.t('pricing.currentPlan')),
                    visualDensity: VisualDensity.compact,
                  ),
              ],
            ),
            const SizedBox(height: 12),
            Row(
              crossAxisAlignment: CrossAxisAlignment.end,
              children: [
                Text(
                  plan.price,
                  style: Theme.of(context).textTheme.headlineSmall,
                ),
                if (plan.monthly)
                  Padding(
                    padding: const EdgeInsets.only(left: 4, bottom: 3),
                    child: Text(strings.t('pricing.perMonth')),
                  ),
              ],
            ),
            const SizedBox(height: 10),
            Text(
              plan.description,
              style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                    color: palette.muted,
                  ),
            ),
            const SizedBox(height: 14),
            Expanded(
              child: ListView.separated(
                physics: const NeverScrollableScrollPhysics(),
                itemCount: plan.features.length,
                separatorBuilder: (context, index) => const SizedBox(height: 8),
                itemBuilder: (context, index) {
                  return Row(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Icon(Icons.check_circle, color: palette.accent, size: 19),
                      const SizedBox(width: 8),
                      Expanded(child: Text(plan.features[index])),
                    ],
                  );
                },
              ),
            ),
            SizedBox(
              width: double.infinity,
              child: FilledButton.tonal(
                onPressed: plan.current ? null : () {},
                child: Text(
                  plan.current
                      ? strings.t('pricing.ctaCurrent')
                      : strings.t('pricing.ctaUpgrade'),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _PlanDefinition {
  const _PlanDefinition({
    required this.name,
    required this.price,
    required this.description,
    required this.features,
    this.monthly = true,
    this.highlighted = false,
    this.current = false,
  });

  final String name;
  final String price;
  final String description;
  final List<String> features;
  final bool monthly;
  final bool highlighted;
  final bool current;
}

List<_PlanDefinition> _plans(AppStrings strings) {
  return [
    _PlanDefinition(
      name: strings.t('pricing.plans.free.name'),
      price: strings.t('pricing.plans.free.price'),
      description: strings.t('pricing.plans.free.description'),
      monthly: false,
      current: true,
      features: [
        strings.t('mobile.freeFeature1'),
        strings.t('mobile.freeFeature2'),
        strings.t('mobile.freeFeature3'),
      ],
    ),
    _PlanDefinition(
      name: strings.t('pricing.plans.premium.name'),
      price: strings.t('pricing.plans.premium.price'),
      description: strings.t('pricing.plans.premium.description'),
      highlighted: true,
      features: [
        strings.t('mobile.premiumFeature1'),
        strings.t('mobile.premiumFeature2'),
        strings.t('mobile.premiumFeature3'),
      ],
    ),
    _PlanDefinition(
      name: strings.t('pricing.plans.max.name'),
      price: strings.t('pricing.plans.max.price'),
      description: strings.t('pricing.plans.max.description'),
      features: [
        strings.t('mobile.maxFeature1'),
        strings.t('mobile.maxFeature2'),
        strings.t('mobile.maxFeature3'),
      ],
    ),
  ];
}
