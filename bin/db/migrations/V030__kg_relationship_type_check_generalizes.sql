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

-- V030: Extend the closed kg_edges.relationship_type vocabulary with
-- `generalizes` — the inverse direction of is_a/instance_of, useful when
-- the source is the more abstract concept and the target a specific case
-- (e.g. "Tree generalizes BinaryTree"). Keep this list in sync with
-- ExtractionPromptBuilder.RELATION_TYPES,
-- DefaultKnowledgeGraphService.RELATIONSHIP_TYPE_VOCABULARY,
-- and bin/db/normalize-relationship-types.sql.
--
-- Idempotent: only drops-and-re-adds when the existing constraint definition
-- does not already mention `generalizes`. Re-running this against a DB that
-- already has V030 applied is a no-op.

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conname = 'kg_edges_relationship_type_check'
           AND conrelid = 'kg_edges'::regclass
           AND pg_get_constraintdef( oid ) NOT LIKE '%generalizes%'
    ) THEN
        ALTER TABLE kg_edges DROP CONSTRAINT kg_edges_relationship_type_check;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conname = 'kg_edges_relationship_type_check'
           AND conrelid = 'kg_edges'::regclass
    ) THEN
        ALTER TABLE kg_edges
            ADD CONSTRAINT kg_edges_relationship_type_check
            CHECK ( relationship_type IN (
                'related_to', 'part_of', 'contains', 'is_a', 'instance_of',
                'requires', 'enables', 'uses', 'produces', 'replaces',
                'precedes', 'extends', 'implements', 'alternative_to', 'contrasts_with',
                'compatible_with', 'mitigates', 'defines', 'applies_to', 'located_in',
                'generalizes'
            ) );
    END IF;
END
$$;
