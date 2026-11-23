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

-- V096: work management. Requires V086.
--   work_notification.reason gains 'requested': a lead is told when a person who only reports files a request, and
--   when that person comments on a request nobody holds yet.
-- Idempotent: the reason check is dropped by whatever name it has and re-added under one fixed name.
DO $$
DECLARE c RECORD;
BEGIN
    FOR c IN SELECT conname FROM pg_constraint
             WHERE conrelid = 'work_notification'::regclass AND contype = 'c'
               AND pg_get_constraintdef(oid) LIKE '%reason%' LOOP
        EXECUTE format('ALTER TABLE work_notification DROP CONSTRAINT %I', c.conname);
    END LOOP;
END $$;
ALTER TABLE work_notification ADD CONSTRAINT work_notification_reason_chk
    CHECK (reason IN ('assigned','mentioned','watching','acknowledge','claim_expired','webhook_disabled','requested'));
