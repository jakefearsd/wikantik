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

-- V019: Drop the legacy kg_embeddings (ComplEx) and kg_content_embeddings
-- (TF-IDF) tables. Both were created in V004 and have had no readers or
-- writers in production code since the unified Ollama-backed embedding
-- stack was introduced. KG-node similarity now derives node vectors as the
-- centroid of mention-chunk vectors (kg_nodes -> chunk_entity_mentions ->
-- content_chunk_embeddings), so neither legacy table is referenced anywhere.
--
-- No FKs reference these tables; no application grants need to be revoked
-- (DROP TABLE removes them implicitly). Idempotent.

DROP TABLE IF EXISTS kg_embeddings;
DROP TABLE IF EXISTS kg_content_embeddings;
