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

-- V034: Page ownership + comment mentions.
-- DDL only — initial population of page_owners is lazy via
-- PageOwnerService.getOwner() (find-or-create from frontmatter `author`).
-- Mentions are written at comment save/edit time by MentionService.

-- ---- page ownership -------------------------------------------------------
-- canonical_id is NOT a foreign key to page_canonical_ids; the structural
-- index synthesises in-memory ULIDs for pages without an authored
-- canonical_id and does NOT persist them, so resolveCanonicalIdFromSlug can
-- legitimately return an id absent from that table. (Same rationale as V033.)
-- owner_login NULL ⇒ orphaned; PageOwnerService applies an admin fallback at
-- read time.
CREATE TABLE IF NOT EXISTS page_owners (
    canonical_id TEXT PRIMARY KEY,
    owner_login  TEXT,
    assigned_by  TEXT NOT NULL,
    assigned_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_page_owners_owner_login
    ON page_owners (owner_login);

CREATE INDEX IF NOT EXISTS idx_page_owners_orphaned
    ON page_owners (canonical_id)
    WHERE owner_login IS NULL;

GRANT SELECT, INSERT, UPDATE, DELETE ON page_owners TO :app_user;

-- ---- comment mentions -----------------------------------------------------
CREATE TABLE IF NOT EXISTS comment_mentions (
    id                UUID PRIMARY KEY,
    comment_id        UUID NOT NULL REFERENCES comments(id) ON DELETE CASCADE,
    mentioned_login   TEXT NOT NULL,
    mentioning_login  TEXT NOT NULL,
    is_owner_mention  BOOLEAN NOT NULL DEFAULT FALSE,
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    read_at           TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uq_comment_mentions UNIQUE (comment_id, mentioned_login)
);

CREATE INDEX IF NOT EXISTS idx_comment_mentions_feed
    ON comment_mentions (mentioned_login, read_at, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_comment_mentions_comment_id
    ON comment_mentions (comment_id);

GRANT SELECT, INSERT, UPDATE, DELETE ON comment_mentions TO :app_user;
