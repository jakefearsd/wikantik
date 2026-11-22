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

-- V088: attachments on work items (work management part 7).
--
-- A file attached to an item (and optionally to one of its comments). Metadata and bytes are kept apart so
-- listing never reads a blob. Removal keeps the metadata row (removed_at) and the item's event, and deletes
-- the bytes: nothing is hard-deleted from work_attachment, but the blob table is the one the app role may DELETE
-- from. The bytes live in PostgreSQL so a backup of the database is a backup of the files.

CREATE TABLE IF NOT EXISTS work_attachment (
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    item_id              BIGINT NOT NULL REFERENCES work_item (id),
    comment_id           BIGINT REFERENCES work_comment (id),
    filename             TEXT NOT NULL CHECK (filename <> '' AND char_length(filename) <= 200),
    content_type         TEXT NOT NULL CHECK (content_type <> '' AND char_length(content_type) <= 127),
    size_bytes           BIGINT NOT NULL CHECK (size_bytes >= 0),
    sha256               TEXT NOT NULL CHECK (char_length(sha256) = 64),
    uploaded_by          TEXT NOT NULL CHECK (uploaded_by <> ''),
    uploaded_by_agent_id BIGINT REFERENCES work_agent (id),
    uploaded_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    removed_at           TIMESTAMP WITH TIME ZONE
);

CREATE INDEX IF NOT EXISTS idx_work_attachment_item ON work_attachment (item_id);

CREATE TABLE IF NOT EXISTS work_attachment_blob (
    attachment_id BIGINT PRIMARY KEY REFERENCES work_attachment (id),
    content       BYTEA NOT NULL
);

GRANT SELECT, INSERT, UPDATE ON work_attachment TO :app_user;
GRANT SELECT, INSERT, DELETE ON work_attachment_blob TO :app_user;
