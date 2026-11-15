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
-- V074: work management core (phase 1) - projects, members, products, items, revisions, the event log,
-- comments, mentions and links. work_event is append-only and its type has no CHECK so later phases can add
-- event types. Policy columns (default_autonomy, autonomy_ceiling, deploy_gate, autonomy, done_means,
-- allow_direct) exist with defaults but drive no behaviour yet. Idempotent.

CREATE TABLE IF NOT EXISTS work_project (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    key              TEXT        NOT NULL UNIQUE CHECK (key ~ '^[A-Z][A-Z0-9]{1,9}$'),
    name             TEXT        NOT NULL,
    description      TEXT        NOT NULL DEFAULT '',
    next_seq         INTEGER     NOT NULL DEFAULT 1,
    default_autonomy TEXT        NOT NULL DEFAULT 'delegated',
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    archived_at      TIMESTAMP WITH TIME ZONE
);

CREATE TABLE IF NOT EXISTS work_member (
    project_id     BIGINT NOT NULL REFERENCES work_project (id),
    principal_kind TEXT   NOT NULL CHECK (principal_kind IN ('user', 'group', 'agent')),
    principal      TEXT   NOT NULL,
    role           TEXT   NOT NULL CHECK (role IN ('viewer', 'member', 'lead')),
    PRIMARY KEY (project_id, principal_kind, principal)
);

CREATE TABLE IF NOT EXISTS work_product (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    key              TEXT NOT NULL UNIQUE CHECK (key ~ '^[a-z][a-z0-9-]{1,39}$'),
    name             TEXT NOT NULL,
    repo_url         TEXT,
    default_branch   TEXT,
    wiki_cluster     TEXT,
    autonomy_ceiling TEXT NOT NULL DEFAULT 'delegated',
    deploy_gate      TEXT NOT NULL DEFAULT 'human',
    archived_at      TIMESTAMP WITH TIME ZONE
);

CREATE TABLE IF NOT EXISTS work_item (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    project_id  BIGINT           NOT NULL REFERENCES work_project (id),
    seq         INTEGER          NOT NULL,
    level       TEXT             NOT NULL CHECK (level IN ('epic', 'story', 'task')),
    parent_id   BIGINT           REFERENCES work_item (id),
    product_id  BIGINT           REFERENCES work_product (id),
    kind        TEXT             NOT NULL CHECK (kind IN ('software', 'docs', 'admin')),
    title       TEXT             NOT NULL,
    body        TEXT             NOT NULL DEFAULT '',
    status      TEXT             NOT NULL DEFAULT 'draft'
                CHECK (status IN ('draft', 'refining', 'ready', 'in_progress', 'review', 'done', 'cancelled')),
    priority    SMALLINT         NOT NULL DEFAULT 3 CHECK (priority BETWEEN 1 AND 4),
    rank        DOUBLE PRECISION NOT NULL,
    due_on      DATE,
    assignee    TEXT,
    reporter    TEXT             NOT NULL,
    autonomy    TEXT,
    done_means  TEXT,
    allow_direct BOOLEAN,
    version     INTEGER          NOT NULL DEFAULT 1,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    closed_at   TIMESTAMP WITH TIME ZONE,
    UNIQUE (project_id, seq)
);

CREATE INDEX IF NOT EXISTS idx_work_item_board    ON work_item (project_id, status, rank);
CREATE INDEX IF NOT EXISTS idx_work_item_parent   ON work_item (parent_id);
CREATE INDEX IF NOT EXISTS idx_work_item_assignee ON work_item (assignee);
CREATE INDEX IF NOT EXISTS idx_work_item_due      ON work_item (due_on) WHERE due_on IS NOT NULL;

CREATE TABLE IF NOT EXISTS work_item_revision (
    item_id   BIGINT  NOT NULL REFERENCES work_item (id),
    version   INTEGER NOT NULL,
    title     TEXT    NOT NULL,
    body      TEXT    NOT NULL,
    edited_by TEXT    NOT NULL,
    edited_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    PRIMARY KEY (item_id, version)
);

CREATE TABLE IF NOT EXISTS work_event (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    project_id BIGINT NOT NULL REFERENCES work_project (id),
    item_id    BIGINT REFERENCES work_item (id),
    at         TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    actor      TEXT   NOT NULL,
    actor_kind TEXT   NOT NULL DEFAULT 'user',
    agent_id   BIGINT,
    type       TEXT   NOT NULL,
    from_value TEXT,
    to_value   TEXT,
    note       TEXT
);

CREATE INDEX IF NOT EXISTS idx_work_event_project ON work_event (project_id, at DESC);
CREATE INDEX IF NOT EXISTS idx_work_event_item    ON work_event (item_id, at);

CREATE TABLE IF NOT EXISTS work_comment (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    item_id    BIGINT NOT NULL REFERENCES work_item (id),
    author     TEXT   NOT NULL,
    body       TEXT   NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    edited_at  TIMESTAMP WITH TIME ZONE,
    removed_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX IF NOT EXISTS idx_work_comment_item ON work_comment (item_id, created_at);

CREATE TABLE IF NOT EXISTS work_mention (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    item_id    BIGINT NOT NULL REFERENCES work_item (id),
    comment_id BIGINT REFERENCES work_comment (id),
    mentioned  TEXT   NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_work_mention_mentioned ON work_mention (mentioned, created_at DESC);

CREATE TABLE IF NOT EXISTS work_link (
    id      BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    item_id BIGINT NOT NULL REFERENCES work_item (id),
    kind    TEXT   NOT NULL CHECK (kind IN ('page', 'issue', 'url', 'blocked_by', 'commit')),
    target  TEXT   NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_work_link_item ON work_link (item_id);

-- Nothing is hard-deleted except membership and links. work_event is append-only by convention:
-- the other tables share the uniform grant; the application never updates it.
GRANT SELECT, INSERT, UPDATE ON work_project, work_product, work_item, work_item_revision, work_event,
    work_comment, work_mention TO :app_user;
GRANT SELECT, INSERT, UPDATE, DELETE ON work_member, work_link TO :app_user;
