-- Licensed to the Apache Software Foundation (ASF) under one
-- or more contributor license agreements.  See the NOTICE file
-- distributed with this work for additional information
-- regarding copyright ownership.  The ASF licenses this file
-- to you under the Apache License, Version 2.0 (the
-- "License"); you may not use this file except in compliance
-- with the License.  You may obtain a copy of the License at
--
--    http://www.apache.org/licenses/LICENSE-2.0
--
-- Unless required by applicable law or agreed to in writing,
-- software distributed under the License is distributed on an
-- "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
-- KIND, either express or implied.  See the License for the
-- specific language governing permissions and limitations
-- under the License.

-- V089: code-host integration (work management part 5).
--
-- The code host (GitHub, or a generic sender) reports branches, pull requests, merges and direct pushes over a
-- signed webhook; the wiki receives only, it never calls the host and stores no API token.
--
-- work_change columns:
--   first_commit_at       earliest commit time seen for the branch (only ever moves earlier)
--   source                'reported' (an agent or person told the wiki) or 'codehost' (the webhook did)
--   external_*_by         the code-host username or name when no wiki login matches the person
--   review_number         the pull request number, to find the change a later delivery is about (one change per
--                         item and pull request: idx_work_change_review)
--   policy_flag           a merge outside policy: 'unreviewed' or 'direct'; cleared only when an approval the host
--                         stamped no later than the merge, of the merged commit, arrives after it
--   flag_acknowledged_*   the lead who acknowledged the flag, and when
--   superseded_by         a direct change later recognised as a pull request's merge (never counted twice)
--   opened_by             may now be null when only an outside author is known (work_change_opener_chk)
-- idx_work_change_direct admits one code-host direct change per item and commit, so a redelivered push cannot
-- record a second one; legacy 'reported' rows are outside it, so old duplicates cannot fail the migration.
--
-- work_product_hook holds one webhook secret per product. The secret is stored as issued because verifying an
-- HMAC needs it (unlike agent tokens it cannot be hashed); it is never returned after creation, never put in an
-- event value, a log line or an audit detail. work_codehost_identity remembers which wiki login a code-host
-- username was matched to (learned from the first email match), per host.
-- Nothing is deleted from them: the application role gets no DELETE on either table.

ALTER TABLE work_change ADD COLUMN IF NOT EXISTS first_commit_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE work_change ADD COLUMN IF NOT EXISTS source TEXT NOT NULL DEFAULT 'reported'
    CHECK (source IN ('reported', 'codehost'));
ALTER TABLE work_change ADD COLUMN IF NOT EXISTS external_opened_by TEXT;
ALTER TABLE work_change ADD COLUMN IF NOT EXISTS external_reviewed_by TEXT;
ALTER TABLE work_change ADD COLUMN IF NOT EXISTS external_merged_by TEXT;
ALTER TABLE work_change ADD COLUMN IF NOT EXISTS review_number TEXT;
ALTER TABLE work_change ADD COLUMN IF NOT EXISTS policy_flag TEXT CHECK (policy_flag IN ('unreviewed', 'direct'));
ALTER TABLE work_change ADD COLUMN IF NOT EXISTS flag_acknowledged_by TEXT;
ALTER TABLE work_change ADD COLUMN IF NOT EXISTS flag_acknowledged_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE work_change ADD COLUMN IF NOT EXISTS superseded_by BIGINT REFERENCES work_change (id);
ALTER TABLE work_change ALTER COLUMN opened_by DROP NOT NULL;
ALTER TABLE work_change DROP CONSTRAINT IF EXISTS work_change_opener_chk;
ALTER TABLE work_change ADD CONSTRAINT work_change_opener_chk
    CHECK (opened_by IS NOT NULL OR external_opened_by IS NOT NULL);
CREATE UNIQUE INDEX IF NOT EXISTS idx_work_change_direct ON work_change (item_id, merged_sha)
    WHERE merge_kind = 'direct' AND source = 'codehost';
CREATE INDEX IF NOT EXISTS idx_work_change_flag ON work_change (item_id)
    WHERE policy_flag IS NOT NULL AND flag_acknowledged_at IS NULL AND superseded_by IS NULL;
CREATE UNIQUE INDEX IF NOT EXISTS idx_work_change_review ON work_change (item_id, review_number)
    WHERE review_number IS NOT NULL;

CREATE TABLE IF NOT EXISTS work_product_hook (
    product_id       BIGINT PRIMARY KEY REFERENCES work_product (id),
    secret           TEXT NOT NULL,
    created_by       TEXT NOT NULL,
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    last_received_at TIMESTAMP WITH TIME ZONE,
    disabled_at      TIMESTAMP WITH TIME ZONE
);

CREATE TABLE IF NOT EXISTS work_codehost_identity (
    host       TEXT NOT NULL,
    username   TEXT NOT NULL,
    login      TEXT NOT NULL,
    learned_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    PRIMARY KEY (host, username)
);

GRANT SELECT, INSERT, UPDATE ON work_product_hook, work_codehost_identity TO :app_user;

-- Order and replay (part 5, fix rounds 1 and 2). Deliveries reach the wiki in any order and may be repeated; the record
-- must come out the same whatever the order, so it keeps the code host's own times rather than arrival order.
--   One pull request is one change for its whole history (idx_work_change_review is unique): closing it abandons
--   that change and reopening it revives the same row.
--   lifecycle_at, lifecycle_kind   the winning lifecycle event of the pull request (opened, reopened, closed,
--                          merged), ordered by host time and then merged > closed > reopened > opened; an event with
--                          an older key changes no state. Null on a change no lifecycle event has reached.
--   work_codehost_lifecycle    every lifecycle event the host reported for a change, once, so a repeated one writes
--                          no second event row (at most 200 per change; past that the register still moves)
--   work_codehost_head     every commit the host reported as a change's head, with the earliest host time it was
--                          reported (the head is the latest of these by host time), and the earliest approval the
--                          host reported for that commit (an approval belongs to the commit it approved); only the
--                          200 most recent reports per change are kept, so the application role may DELETE here
-- idx_work_change_live_branch now holds only changes no pull request is linked to: two pull requests may come from
-- one branch (a backport, a closed one replaced by a new one) and each keeps its own change.
ALTER TABLE work_change ADD COLUMN IF NOT EXISTS lifecycle_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE work_change ADD COLUMN IF NOT EXISTS lifecycle_kind TEXT
    CHECK (lifecycle_kind IN ('opened', 'reopened', 'closed', 'merged'));
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_indexes WHERE indexname = 'idx_work_change_live_branch'
                   AND indexdef LIKE '%review_number IS NULL%') THEN
        DROP INDEX IF EXISTS idx_work_change_live_branch;
        CREATE UNIQUE INDEX idx_work_change_live_branch ON work_change (item_id, branch)
            WHERE state IN ('open', 'in_review') AND review_number IS NULL;
    END IF;
END $$;

CREATE TABLE IF NOT EXISTS work_codehost_head (
    change_id            BIGINT NOT NULL REFERENCES work_change (id),
    sha                  TEXT NOT NULL,
    seen_at              TIMESTAMP WITH TIME ZONE,
    approved_by          TEXT,
    external_approved_by TEXT,
    approved_at          TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (change_id, sha)
);

CREATE TABLE IF NOT EXISTS work_codehost_lifecycle (
    change_id BIGINT NOT NULL REFERENCES work_change (id),
    kind      TEXT NOT NULL CHECK (kind IN ('opened', 'reopened', 'closed', 'merged')),
    at        TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (change_id, kind, at)
);

GRANT SELECT, INSERT, UPDATE, DELETE ON work_codehost_head TO :app_user;
GRANT SELECT, INSERT ON work_codehost_lifecycle TO :app_user;
