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
-- V073: agent feedback loop on comments. agent_available marks a thread agents may respond to;
-- via_agent marks a comment posted through an agent credential; author_could_edit snapshots whether
-- the author held edit permission on the page when writing (the trust signal). Idempotent.

ALTER TABLE comment_threads ADD COLUMN IF NOT EXISTS agent_available BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE comments ADD COLUMN IF NOT EXISTS via_agent BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE comments ADD COLUMN IF NOT EXISTS author_could_edit BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX IF NOT EXISTS idx_comment_threads_agent_inbox
    ON comment_threads (status, created_at) WHERE agent_available;
