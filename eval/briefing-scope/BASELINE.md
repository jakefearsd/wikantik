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

## After Phase 0 (2026-10-23)

Base URL: `https://wiki.wikantik.com` -- 2 of 8 cases passed.

| case | result | separation | diversity | violations |
|------|--------|-----------:|----------:|------------|
| `sa-roller-telemetry` | PASS | 0.79 | 0.33 | - |
| `roller-telemetry-prefer` | FAIL | 0.94 | 0.17 | required item missing: SimpleAgilityTelemetryContract; RollerTelemetryHandoffSpec has 11 sections (cap 4) |
| `roller-telemetry-strict` | FAIL | 1.00 | 0.09 | required item missing: SimpleAgilityTelemetryContract; RollerTelemetryHandoffSpec has 11 sections (cap 4) |
| `jakemon-push-feed` | FAIL | 0.72 | 0.17 | forbidden body page present: IncidentWebmasterProviderStale |
| `stockroom-itemsold` | PASS | 0.64 | 0.33 | - |
| `nocluster-lease-guard` | FAIL | n/a | 0.17 | BackgroundJobProcessing has 11 sections (cap 4) |
| `wikidev-trace-id` | FAIL | 0.71 | 0.64 | forbidden body page present: LoginHelp; forbidden body page present: RollerTelemetryHandoffSpec |
| `roller-unknown-arg` | FAIL | 0.94 | 0.17 | expected warning not found: /^unknown argument: scope/ |

Raw per-case counts (token estimate = chars / 4):

```json
{
  "sa-roller-telemetry": {
    "sections": 12,
    "distinct_pages": 4,
    "included_items": 1,
    "warnings": 0,
    "in_scope_tokens": 3506,
    "total_tokens": 4429,
    "used_tokens": 4439
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
    "distinct_pages": 4,
    "included_items": 2,
    "warnings": 0,
    "in_scope_tokens": 3235,
    "total_tokens": 5044,
    "used_tokens": 5055
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

> Note: the "Before Phase 0" run above was taken while the Phase 0 content moves were being applied on prod, so `sa-roller-telemetry` already passes there; the true pre-move state for that case is in the first commit of the harness (eb801915f5), where it failed on `StockroomPhase7PhotoIdentificationPlan` as body text.

## After Phase 2 (2026-10-24, release 2.4.64)

Base URL: `https://wiki.wikantik.com` -- 7 of 8 cases passed.

| case | result | separation | diversity | violations |
|------|--------|-----------:|----------:|------------|
| `sa-roller-telemetry` | PASS | 1.00 | 0.39 | - |
| `roller-telemetry-prefer` | PASS | 1.00 | 0.62 | - |
| `roller-telemetry-strict` | PASS | 1.00 | 0.62 | - |
| `jakemon-push-feed` | FAIL | 0.46 | 0.47 | forbidden body page present: IncidentHostNotReporting; forbidden body page present: IncidentWikantikOffsiteBackupStale |
| `stockroom-itemsold` | PASS | 1.00 | 0.60 | - |
| `nocluster-lease-guard` | PASS | n/a | 0.64 | - |
| `wikidev-trace-id` | PASS | 1.00 | 0.50 | - |
| `roller-unknown-arg` | PASS | 1.00 | 0.62 | - |

Raw per-case counts (token estimate = chars / 4):

```json
{
  "sa-roller-telemetry": {
    "sections": 18,
    "distinct_pages": 7,
    "included_items": 1,
    "warnings": 0,
    "in_scope_tokens": 5774,
    "total_tokens": 5774,
    "used_tokens": 5787
  },
  "roller-telemetry-prefer": {
    "sections": 8,
    "distinct_pages": 5,
    "included_items": 3,
    "warnings": 0,
    "in_scope_tokens": 5928,
    "total_tokens": 5928,
    "used_tokens": 5935
  },
  "roller-telemetry-strict": {
    "sections": 8,
    "distinct_pages": 5,
    "included_items": 3,
    "warnings": 0,
    "in_scope_tokens": 5928,
    "total_tokens": 5928,
    "used_tokens": 5935
  },
  "jakemon-push-feed": {
    "sections": 19,
    "distinct_pages": 9,
    "included_items": 2,
    "warnings": 1,
    "in_scope_tokens": 2712,
    "total_tokens": 5876,
    "used_tokens": 5892
  },
  "stockroom-itemsold": {
    "sections": 10,
    "distinct_pages": 6,
    "included_items": 3,
    "warnings": 0,
    "in_scope_tokens": 5694,
    "total_tokens": 5694,
    "used_tokens": 5704
  },
  "nocluster-lease-guard": {
    "sections": 22,
    "distinct_pages": 14,
    "included_items": 0,
    "warnings": 0,
    "in_scope_tokens": 0,
    "total_tokens": 3325,
    "used_tokens": 3337
  },
  "wikidev-trace-id": {
    "sections": 8,
    "distinct_pages": 4,
    "included_items": 4,
    "warnings": 1,
    "in_scope_tokens": 5598,
    "total_tokens": 5598,
    "used_tokens": 5608
  },
  "roller-unknown-arg": {
    "sections": 8,
    "distinct_pages": 5,
    "included_items": 3,
    "warnings": 1,
    "in_scope_tokens": 5928,
    "total_tokens": 5928,
    "used_tokens": 5935
  }
}
```

> 7 of 8 pass on 2.4.64. `jakemon-push-feed` fails because tier-1 in-degree ranking places incident runbooks (a dense 42-page link web) above the contracts, so two runbook bodies land in standing context; the task-relevant sections are correct. Its separation score of 0.46 is a harness limitation (the sitemap exposes only each page's primary cluster, so `SimpleAgilityObservabilityPlane`/`SimpleAgilityTelemetryContract`, multi-members of `jakemon`, count as out-of-scope). Fixed in 2.4.65 by ordering non-runbook members before runbooks (spec amendment 11).
