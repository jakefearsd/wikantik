# wikantik-connectors

Module notes, loaded when working under this directory (moved from the root CLAUDE.md).

- **wikantik-connectors**: Seven connector types syncing into derived pages via `SyncOrchestrator` (`poll()` never throws — load-bearing). Six are admin-creatable (`ConnectorConfigCodec.UI_TYPES`); `filesystem` stays properties-only by design (D9: it reads host paths). Configs live in `connector_configs`, hot-applied without restart; secrets only in the encrypted `connector_credentials`. Kill switch `wikantik.connectors.enabled` (default true). UI `/admin/connectors`; guide `docs/admin/Connectors.md`.
