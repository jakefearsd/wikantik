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
-- V070: Drop the retired Knowledge Graph (LLM-extracted entities, their curation and policy, hub
-- discovery). The KG was switched off in 2.5.12 and its code deleted in the release carrying this
-- migration (docs/superpowers/plans/2026-11-09-kg-deletion.md). Production's KG tables were archived
-- with pg_dump to the backup NAS before this ran; restoring that dump is the only way back.
--
-- NOT dropped: kg_content_chunks and content_chunk_embeddings, the dense-retrieval chunk and embedding
-- tables (KG-prefixed for historical reasons only).
--
-- Order: dependents first. chunk_entity_mentions references both kg_content_chunks (kept) and
-- kg_nodes; kg_edges / kg_node_embeddings reference kg_nodes; kg_proposal_reviews references
-- kg_proposals. No non-KG table references any of these. DROP TABLE revokes grants implicitly.
-- Idempotent.
DROP TABLE IF EXISTS chunk_entity_mentions;
DROP TABLE IF EXISTS kg_node_embeddings;
DROP TABLE IF EXISTS kg_edge_audit;
DROP TABLE IF EXISTS kg_edges;
DROP TABLE IF EXISTS kg_proposal_reviews;
DROP TABLE IF EXISTS kg_judge_timeouts;
DROP TABLE IF EXISTS kg_proposals;
DROP TABLE IF EXISTS kg_rejections;
DROP TABLE IF EXISTS kg_nodes;
DROP TABLE IF EXISTS kg_excluded_pages;
DROP TABLE IF EXISTS kg_cluster_policy;
DROP TABLE IF EXISTS kg_policy_audit;
DROP TABLE IF EXISTS hub_proposals;
DROP TABLE IF EXISTS hub_discovery_proposals;
DROP TABLE IF EXISTS hub_centroids;
