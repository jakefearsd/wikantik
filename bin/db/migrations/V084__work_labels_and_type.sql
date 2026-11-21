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

-- V084: item type and labels (work management part 1).
--
-- Every item has a type (feature, bug or chore; the default for existing rows is feature) and a set of labels
-- (lowercase, normalised by com.wikantik.work.Labels; the default is none). The GIN index serves label filters.
-- Additive and idempotent: no data backfill, no grant change (V074 grants SELECT, INSERT, UPDATE on work_item).

ALTER TABLE work_item ADD COLUMN IF NOT EXISTS type TEXT NOT NULL DEFAULT 'feature'
    CHECK (type IN ('feature', 'bug', 'chore'));
ALTER TABLE work_item ADD COLUMN IF NOT EXISTS labels TEXT[] NOT NULL DEFAULT '{}';
CREATE INDEX IF NOT EXISTS idx_work_item_labels ON work_item USING GIN (labels);
