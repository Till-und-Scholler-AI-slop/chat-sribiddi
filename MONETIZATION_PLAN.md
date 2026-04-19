# MONETIZATION_PLAN (BILL-1)

Stand: 2026-04-19

## 1) Finales Tier-Modell & Feature-Matrix

| Tier | Preis (brutto, inkl. 19% USt.) | Preis (netto) | Modellzugriff | Features |
|---|---:|---:|---|---|
| Free | 0,00 € / Monat | 0,00 € | Nur Modelle mit `input <= 2,00 USD / 1M` **und** `output <= 5,00 USD / 1M` | Basis-Chat |
| Premium | 14,99 € / Monat | 12,60 € | Alle Free-Modelle + alle Modelle oberhalb der Free-Schwelle | Höhere Limits, produktiver Einsatz |
| Max | 39,99 € / Monat | 33,61 € | Gleicher Modellzugriff wie Premium (keine harte Modell-ID-Whitelist), dafür maximale Limits | Power-User / Heavy Usage |

Zusätzliche Jahrespreise (mit Rabatt, für BILL-10 Stripe-Produkte):

| Tier | Preis (brutto, Jahr) | Preis (netto, Jahr) |
|---|---:|---:|
| Premium | 149,90 € | 125,97 € |
| Max | 399,90 € | 336,05 € |

## 2) Preisvalidierung (USt. + Stripe-Fees)

Annahmen:
- USt.: 19% (DE)
- Stripe Fee (Card, Basisannahme): `1,5% + 0,25 €` pro erfolgreicher Zahlung

Monatliche Plausibilisierung:

| Tier | Brutto | Netto (Brutto / 1,19) | Stripe-Fee | Erlös nach USt.+Fee |
|---|---:|---:|---:|---:|
| Premium | 14,99 € | 12,60 € | 0,47 € | 12,12 € |
| Max | 39,99 € | 33,61 € | 0,85 € | 32,76 € |

## 3) YAML-Snippet (BILL-15, ohne harte Modell-Whitelist)

```yaml
app:
  billing:
    model-access:
      strategy: price-threshold
      thresholds-usd-per-million:
        input: 2.0
        output: 5.0
      rules:
        - name: FREE_ALLOWED
          condition: "inputUsdPerMillion <= 2.0 && outputUsdPerMillion <= 5.0"
          availability: ALWAYS
          requiredPlan: null
        - name: PAID_REQUIRED
          condition: "inputUsdPerMillion > 2.0 || outputUsdPerMillion > 5.0"
          availability: UPGRADE_REQUIRED
          requiredPlan: PREMIUM
```

Entscheidung: **keine hardcodierte Modell-ID-Whitelist**. Zugriff wird ausschließlich über Preisschwellen (aus dem Modellkatalog) entschieden.

## 4) Stakeholder-Sign-off (dokumentiert)

- 2026-04-19 — Produktentscheidung für BILL-1 dokumentiert:
  - Keine hardcodierte Whitelist
  - Paid-Grenze: `input > 2.0 USD / 1M` oder `output > 5.0 USD / 1M`
  - Quelle: Issue-Anweisung zu #149 (BILL-1)
