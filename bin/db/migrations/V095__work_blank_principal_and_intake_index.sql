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

-- V095: work management hardening (#157, lane D). Requires V074 and V091.
--   1. work_member.principal may not be blank. A blank principal (an agent row with no id, a user or group row with an
--      empty name) would match any actor whose own name binds as blank. The constraint is added NOT VALID, so it binds
--      every new row at once, and validated only when no existing row is blank (a deployment that has one keeps the
--      constraint unvalidated, with a notice, until the row is repaired and the migration is applied again).
--   2. idx_work_item_reporter_created serves the reporter's daily filing count (wikantik.work.intake.maxPerDay).
-- Idempotent: the constraint is dropped if present and re-added; the index is IF NOT EXISTS.
ALTER TABLE work_member DROP CONSTRAINT IF EXISTS work_member_principal_nonblank_chk;
ALTER TABLE work_member ADD CONSTRAINT work_member_principal_nonblank_chk
    CHECK (principal !~ '^\s*$') NOT VALID;
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM work_member WHERE principal ~ '^\s*$') THEN
        RAISE NOTICE 'work_member holds a blank principal; work_member_principal_nonblank_chk stays NOT VALID until it is removed';
    ELSE
        ALTER TABLE work_member VALIDATE CONSTRAINT work_member_principal_nonblank_chk;
    END IF;
END
$$;

-- Attachment bytes are already-compressed formats: storing them uncompressed (still out of line) lets substring()
-- read a piece without decompressing from the start each time. Affects rows written from now on.
ALTER TABLE work_attachment_blob ALTER COLUMN content SET STORAGE EXTERNAL;

CREATE INDEX IF NOT EXISTS idx_work_item_reporter_created ON work_item (reporter, created_at)
    WHERE reporter_agent_id IS NULL;
