## Before Phase 0 (2026-10-23)

Base URL: `https://wiki.wikantik.com` -- 1 of 8 cases passed.

| case | result | separation | diversity | violations |
|------|--------|-----------:|----------:|------------|
| `sa-roller-telemetry` | FAIL | 0.81 | 0.17 | forbidden body page present: StockroomPhase7PhotoIdentificationPlan |
| `roller-telemetry-prefer` | FAIL | 0.94 | 0.17 | required item missing or not included: SimpleAgilityTelemetryContract; RollerTelemetryHandoffSpec has 11 sections (cap 4) |
| `roller-telemetry-strict` | FAIL | 1.00 | 0.09 | required item missing or not included: SimpleAgilityTelemetryContract; RollerTelemetryHandoffSpec has 11 sections (cap 4) |
| `jakemon-push-feed` | FAIL | 0.74 | 0.17 | forbidden body page present: IncidentWebmasterProviderStale; required item missing or not included: SimpleAgilityObservabilityPlane |
| `stockroom-itemsold` | FAIL | 0.00 | 0.67 | required item missing or not included: StockroomHub; forbidden warning present: unknown cluster: stockroom |
| `nocluster-lease-guard` | PASS | n/a | 0.33 | - |
| `wikidev-trace-id` | FAIL | 0.62 | 0.50 | forbidden body page present: RollerTelemetryHandoffSpec |
| `roller-unknown-arg` | FAIL | 0.94 | 0.17 | expected warning not found: /^unknown argument: scope/ |

Raw per-case counts (token estimate = chars / 4):

```json
{
  "sa-roller-telemetry": {
    "sections": 12,
    "distinct_pages": 2,
    "included_items": 2,
    "warnings": 0,
    "in_scope_tokens": 4576,
    "total_tokens": 5680,
    "used_tokens": 5691
  },
  "roller-telemetry-prefer": {
    "sections": 12,
    "distinct_pages": 2,
    "included_items": 2,
    "warnings": 0,
    "in_scope_tokens": 5417,
    "total_tokens": 5765,
    "used_tokens": 5777
  },
  "roller-telemetry-strict": {
    "sections": 11,
    "distinct_pages": 1,
    "included_items": 3,
    "warnings": 0,
    "in_scope_tokens": 5831,
    "total_tokens": 5831,
    "used_tokens": 5843
  },
  "jakemon-push-feed": {
    "sections": 12,
    "distinct_pages": 2,
    "included_items": 2,
    "warnings": 0,
    "in_scope_tokens": 4177,
    "total_tokens": 5657,
    "used_tokens": 5668
  },
  "stockroom-itemsold": {
    "sections": 12,
    "distinct_pages": 8,
    "included_items": 0,
    "warnings": 1,
    "in_scope_tokens": 0,
    "total_tokens": 1851,
    "used_tokens": 1861
  },
  "nocluster-lease-guard": {
    "sections": 12,
    "distinct_pages": 4,
    "included_items": 0,
    "warnings": 0,
    "in_scope_tokens": 0,
    "total_tokens": 1914,
    "used_tokens": 1922
  },
  "wikidev-trace-id": {
    "sections": 12,
    "distinct_pages": 6,
    "included_items": 3,
    "warnings": 0,
    "in_scope_tokens": 3714,
    "total_tokens": 5969,
    "used_tokens": 5979
  },
  "roller-unknown-arg": {
    "sections": 12,
    "distinct_pages": 2,
    "included_items": 2,
    "warnings": 0,
    "in_scope_tokens": 5417,
    "total_tokens": 5765,
    "used_tokens": 5777
  }
}
```
