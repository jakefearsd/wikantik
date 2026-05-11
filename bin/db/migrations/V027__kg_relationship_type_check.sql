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

-- V027: Enforce a closed 20-type vocabulary on kg_edges.relationship_type via
-- a CHECK constraint at the DB boundary. Pairs with the entity-extractor
-- prompt update and the one-shot normalization in
-- bin/db/normalize-relationship-types.sql (which must be run on an existing
-- database BEFORE this migration; fresh installs start empty and apply this
-- cleanly).
--
-- The closed vocabulary covers the semantic axes observed in the corpus —
-- generic association, structural composition, type/instance, dependency,
-- lifecycle, comparison, risk reduction, and reference. Any future extractor
-- that emits a non-vocabulary value gets rejected at INSERT, so model drift
-- can never silently grow the predicate set again.
--
-- Idempotent: wrapped in a DO block that checks pg_constraint before adding.

DO $$
BEGIN
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
                'compatible_with', 'mitigates', 'defines', 'applies_to', 'located_in'
            ) );
    END IF;
END
$$;
