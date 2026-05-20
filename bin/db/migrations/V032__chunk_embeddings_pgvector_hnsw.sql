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

-- V032: pgvector HNSW index for chunk dense retrieval.
-- Adds the pgvector column and HNSW index alongside the legacy BYTEA `vec`
-- column. The BYTEA column stays in place during the dual-write cutover so
-- rollback to the in-memory backend works without a data migration. A later
-- migration drops the legacy columns once the new path has soaked in
-- production. Per the no-data-in-migrations rule, the backfill itself is a
-- one-shot script run by the operator before the flag flip — not part of
-- this migration.

ALTER TABLE content_chunk_embeddings
    ADD COLUMN IF NOT EXISTS embedding vector(1024);

CREATE INDEX IF NOT EXISTS content_chunk_embeddings_hnsw_idx
    ON content_chunk_embeddings
    USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);

GRANT SELECT, INSERT, UPDATE, DELETE ON content_chunk_embeddings TO :app_user;
