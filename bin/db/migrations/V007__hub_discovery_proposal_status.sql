-- Licensed under the Apache License, Version 2.0 (the "License").
-- Migration: Persist dismissed hub discovery proposals
--
-- Adds a status column (pending | dismissed) plus reviewer/timestamp
-- metadata to hub_discovery_proposals. Prior to this migration the
-- dismiss path hard-deleted the row, so identical clusters were
-- continuously rediscovered. HubDiscoveryService now marks rows as
-- 'dismissed' and skips re-proposing clusters whose sorted member set
-- exactly matches an existing dismissed row. Users can clear a
-- dismissed row (individually or in bulk) via the admin UI to re-enable
-- rediscovery. Fully idempotent.
--
-- Depends on V006.

ALTER TABLE hub_discovery_proposals
    ADD COLUMN IF NOT EXISTS status       VARCHAR(20)  NOT NULL DEFAULT 'pending';

ALTER TABLE hub_discovery_proposals
    ADD COLUMN IF NOT EXISTS reviewed_by  VARCHAR(255);

ALTER TABLE hub_discovery_proposals
    ADD COLUMN IF NOT EXISTS reviewed_at  TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_hub_discovery_proposals_status
    ON hub_discovery_proposals ( status );
