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

-- V033: Anchored comment threads (Google-Docs-style comments).
-- Threads carry one TextQuoteSelector anchor; comments are the thread body + replies.
--
-- NOTE: canonical_id is deliberately NOT a foreign key to page_canonical_ids.
-- DefaultStructuralIndexService synthesises an in-memory ULID for pages that
-- lack an authored canonical_id and does NOT persist it to page_canonical_ids,
-- so resolveCanonicalIdFromSlug can legitimately return an id absent from that
-- table. An FK here would make comment creation fail at runtime for such pages.
-- Threads are keyed by canonical_id for rename-stability only.

CREATE TABLE IF NOT EXISTS comment_threads (
    id            UUID PRIMARY KEY,
    canonical_id  TEXT NOT NULL,
    anchor_exact  TEXT NOT NULL,
    anchor_prefix TEXT,
    anchor_suffix TEXT,
    status        TEXT NOT NULL DEFAULT 'open',
    created_by    TEXT NOT NULL,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    resolved_by   TEXT,
    resolved_at   TIMESTAMP WITH TIME ZONE
);

CREATE INDEX IF NOT EXISTS idx_comment_threads_canonical_id
    ON comment_threads (canonical_id);

CREATE TABLE IF NOT EXISTS comments (
    id         UUID PRIMARY KEY,
    thread_id  UUID NOT NULL REFERENCES comment_threads(id) ON DELETE CASCADE,
    author     TEXT NOT NULL,
    body       TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    edited_at  TIMESTAMP WITH TIME ZONE
);

CREATE INDEX IF NOT EXISTS idx_comments_thread_id
    ON comments (thread_id);

GRANT SELECT, INSERT, UPDATE, DELETE ON comment_threads TO :app_user;
GRANT SELECT, INSERT, UPDATE, DELETE ON comments TO :app_user;
