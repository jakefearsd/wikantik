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

-- V083: product setting changes are recorded.
--
-- A product belongs to no project, so its changes cannot live in work_event (project_id is NOT NULL). One row per
-- changed field: name, repo_url, default_branch, wiki_cluster, autonomy_ceiling, deploy_gate, and archived (to_value
-- the instant). Append-only, as work_event is; written in the same transaction as the change.

CREATE TABLE IF NOT EXISTS work_product_event (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    product_id BIGINT NOT NULL REFERENCES work_product (id),
    at         TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    actor      TEXT NOT NULL,
    actor_kind TEXT NOT NULL DEFAULT 'user',
    agent_id   BIGINT,
    field      TEXT NOT NULL,
    from_value TEXT,
    to_value   TEXT
);
CREATE INDEX IF NOT EXISTS idx_work_product_event_product ON work_product_event (product_id, at DESC, id DESC);

GRANT SELECT, INSERT ON work_product_event TO :app_user;
