## Before Phase 0 (2026-10-23)

Base URL: `https://wiki.wikantik.com` -- 1 of 8 cases passed.

| case | result | separation | diversity | violations |
|------|--------|-----------:|----------:|------------|
| `sa-roller-telemetry` | PASS | 0.84 | 0.33 | - |
| `roller-telemetry-prefer` | FAIL | 0.94 | 0.17 | required item missing: SimpleAgilityTelemetryContract; RollerTelemetryHandoffSpec has 11 sections (cap 4) |
| `roller-telemetry-strict` | FAIL | 1.00 | 0.09 | required item missing: SimpleAgilityTelemetryContract; RollerTelemetryHandoffSpec has 11 sections (cap 4) |
| `jakemon-push-feed` | FAIL | 0.72 | 0.17 | forbidden body page present: IncidentWebmasterProviderStale |
| `stockroom-itemsold` | FAIL | 0.20 | 0.25 | required item missing: StockroomHub |
| `nocluster-lease-guard` | FAIL | n/a | 0.17 | BackgroundJobProcessing has 11 sections (cap 4) |
| `wikidev-trace-id` | FAIL | 0.71 | 0.64 | forbidden body page present: LoginHelp; forbidden body page present: RollerTelemetryHandoffSpec |
| `roller-unknown-arg` | FAIL | 0.94 | 0.17 | expected warning not found: /^unknown argument: scope/ |

Raw per-case counts (token estimate = chars / 4):

```json
{
  "sa-roller-telemetry": {
    "sections": 12,
    "distinct_pages": 4,
    "included_items": 2,
    "warnings": 0,
    "in_scope_tokens": 5027,
    "total_tokens": 5950,
    "used_tokens": 5960
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
    "in_scope_tokens": 4106,
    "total_tokens": 5724,
    "used_tokens": 5735
  },
  "stockroom-itemsold": {
    "sections": 12,
    "distinct_pages": 3,
    "included_items": 2,
    "warnings": 0,
    "in_scope_tokens": 1045,
    "total_tokens": 5128,
    "used_tokens": 5138
  },
  "nocluster-lease-guard": {
    "sections": 12,
    "distinct_pages": 2,
    "included_items": 0,
    "warnings": 0,
    "in_scope_tokens": 0,
    "total_tokens": 608,
    "used_tokens": 616
  },
  "wikidev-trace-id": {
    "sections": 11,
    "distinct_pages": 7,
    "included_items": 2,
    "warnings": 0,
    "in_scope_tokens": 4005,
    "total_tokens": 5638,
    "used_tokens": 5648
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
