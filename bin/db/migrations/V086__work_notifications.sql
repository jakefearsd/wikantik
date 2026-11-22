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

-- V086: notifications, watching and webhooks (work management part 3).
--
-- The event log is the outbox: work_event gains tx, the id of the transaction that inserted the row (P3-1), so a
-- reader whose cursor is (tx, event id) never skips an event that commits after a higher id was read. The DEFAULT
-- means the one INSERT INTO work_event needs no change; the ALTER rewrites the table once. work_watch,
-- work_notify_pref and work_webhook are a person's own settings and may delete rows. work_notification is never
-- deleted (no retention in this part). Additive and idempotent: no data backfill.

ALTER TABLE work_event ADD COLUMN IF NOT EXISTS tx xid8 NOT NULL DEFAULT pg_current_xact_id();
CREATE INDEX IF NOT EXISTS idx_work_event_tx ON work_event (tx, id);
CREATE INDEX IF NOT EXISTS idx_work_event_project_tx ON work_event (project_id, tx, id);

CREATE TABLE IF NOT EXISTS work_watch (
  item_id BIGINT NOT NULL REFERENCES work_item (id),
  login   TEXT   NOT NULL,
  state   TEXT   NOT NULL CHECK (state IN ('watching','muted')),
  PRIMARY KEY (item_id, login));
CREATE INDEX IF NOT EXISTS idx_work_watch_login ON work_watch (login);

CREATE TABLE IF NOT EXISTS work_notification (
  id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  recipient  TEXT   NOT NULL,
  event_id   BIGINT NOT NULL REFERENCES work_event (id),
  project_id BIGINT NOT NULL REFERENCES work_project (id),
  item_id    BIGINT REFERENCES work_item (id),
  reason     TEXT   NOT NULL CHECK (reason IN
             ('assigned','mentioned','watching','acknowledge','claim_expired','webhook_disabled')),
  created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
  read_at    TIMESTAMP WITH TIME ZONE,
  emailed_at TIMESTAMP WITH TIME ZONE,
  UNIQUE (recipient, event_id));
CREATE INDEX IF NOT EXISTS idx_work_notification_inbox ON work_notification (recipient, id DESC);
-- The nav polls the unread count and mark-read per recipient; the mention de-dup in the dispatcher looks up by item.
CREATE INDEX IF NOT EXISTS idx_work_notification_unread ON work_notification (recipient, project_id) WHERE read_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_work_notification_item ON work_notification (item_id) WHERE item_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_work_notification_unemailed ON work_notification (created_at) WHERE emailed_at IS NULL;

CREATE TABLE IF NOT EXISTS work_notify_cursor (
  name TEXT PRIMARY KEY, tx xid8 NOT NULL, event_id BIGINT NOT NULL);

CREATE TABLE IF NOT EXISTS work_notify_pref (
  login TEXT PRIMARY KEY,
  email_mode TEXT NOT NULL CHECK (email_mode IN ('each','daily','off')),
  last_digest_on DATE);

CREATE TABLE IF NOT EXISTS work_webhook (
  id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  project_id      BIGINT NOT NULL REFERENCES work_project (id),
  url             TEXT   NOT NULL,
  secret          TEXT,
  format          TEXT   NOT NULL CHECK (format IN ('generic','slack')),
  event_types     TEXT[] NOT NULL DEFAULT '{}',
  owner           TEXT   NOT NULL,
  created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
  cursor_tx       xid8   NOT NULL,
  cursor_event_id BIGINT NOT NULL,
  failing_since   TIMESTAMP WITH TIME ZONE,
  last_error      TEXT,
  last_delivered_at TIMESTAMP WITH TIME ZONE,
  disabled_at     TIMESTAMP WITH TIME ZONE,
  CHECK (format <> 'generic' OR secret IS NOT NULL));
CREATE INDEX IF NOT EXISTS idx_work_webhook_project ON work_webhook (project_id);

GRANT SELECT, INSERT, UPDATE, DELETE ON work_watch, work_webhook, work_notify_pref TO :app_user;
GRANT SELECT, INSERT, UPDATE ON work_notification, work_notify_cursor TO :app_user;
