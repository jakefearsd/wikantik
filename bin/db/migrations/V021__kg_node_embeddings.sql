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

-- V021: Cache for KG-node-level bge-m3 embeddings. Used by the page-extractor
-- to build a per-page top-K dictionary instead of the alphabetical-200 we
-- have today. Re-runs are a no-op when content_hash is unchanged.

CREATE TABLE IF NOT EXISTS kg_node_embeddings (
    node_id      UUID         PRIMARY KEY REFERENCES kg_nodes(id) ON DELETE CASCADE,
    content_hash VARCHAR(64)  NOT NULL,
    embedding    vector(1024) NOT NULL,
    embedded_at  TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS kg_node_embeddings_ivfflat_idx
  ON kg_node_embeddings USING ivfflat (embedding vector_cosine_ops) WITH (lists = 50);

GRANT SELECT, INSERT, UPDATE, DELETE ON kg_node_embeddings TO :app_user;
