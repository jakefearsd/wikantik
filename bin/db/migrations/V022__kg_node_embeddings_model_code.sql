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

-- V022: Tag KG-node embedding cache rows with the producing model so the
-- service no longer silently reuses bge-m3 vectors after swapping to a
-- different embedder. Cache key widens from (node_id) to (node_id, model_code);
-- a single node can now hold one row per model. Legacy rows from V021 receive
-- 'unknown' as a marker — they are stale by definition and the next
-- --rebuild-node-embeddings pass overwrites them.

ALTER TABLE kg_node_embeddings
    ADD COLUMN IF NOT EXISTS model_code VARCHAR(64) NOT NULL DEFAULT 'unknown';

-- Drop the old single-column PK; idempotent.
ALTER TABLE kg_node_embeddings DROP CONSTRAINT IF EXISTS kg_node_embeddings_pkey;

-- Re-key on (node_id, model_code) only if the new PK isn't already in place
-- from a prior partial run.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conrelid = 'kg_node_embeddings'::regclass
          AND contype  = 'p'
    ) THEN
        EXECUTE 'ALTER TABLE kg_node_embeddings
                 ADD CONSTRAINT kg_node_embeddings_pkey
                 PRIMARY KEY (node_id, model_code)';
    END IF;
END$$;
