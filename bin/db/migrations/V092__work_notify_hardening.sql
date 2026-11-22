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

-- V092: notification dispatch hardening (work management #157). work_notify_skipped keeps an event the dispatcher
-- could not turn into notifications (a deterministic failure, tried alone and still failing) so it is not lost: the
-- cursor moves past it and this row says which event, in which transaction, and why. It carries no foreign key:
-- the event log is append-only and the row must survive whatever happens to its subject. Derived data, pruned on
-- the notification retention clock (wikantik.work.notify.retentionDays) together with work_notification, which
-- gets the created_at index the daily prune needs. Idempotent; no data backfill.
CREATE TABLE IF NOT EXISTS work_notify_skipped (
  id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  event_id      BIGINT NOT NULL,
  tx            xid8   NOT NULL,
  error_class   TEXT   NOT NULL,
  error_message TEXT   NOT NULL DEFAULT '',
  skipped_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW());
CREATE INDEX IF NOT EXISTS idx_work_notify_skipped_at ON work_notify_skipped (skipped_at);
CREATE INDEX IF NOT EXISTS idx_work_notification_created ON work_notification (created_at);

GRANT SELECT, INSERT, DELETE ON work_notify_skipped TO :app_user;
GRANT DELETE ON work_notification TO :app_user;
