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

-- V012: Purge links_to edges written by the retired GraphProjector.
--
-- The retired GraphProjector (deleted in cycle 6 of the agent-MCP redesign)
-- used to write every markdown body link as an edge with
-- relationship_type='links_to'. These edges are unambiguously projector-
-- derived — no other write path produces 'links_to' — so this purge is
-- safe and idempotent: re-running finds no rows to delete.
--
-- Frontmatter-derived edges (arbitrary relationship types) are NOT
-- purged here: they may include manually-authored relationships that
-- happen to share a type name. Cycle 3's MCP read-filter hides them from
-- agent consumers; they remain for admin-UI inspection.

DELETE FROM kg_edges WHERE relationship_type = 'links_to';

-- No grants needed — existing grants on kg_edges cover this migration.
