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
-- V072: per-page content-changed date. Moves only on substantial body edits (see ContentChangeRule), so the
-- sitemap lastmod and JSON-LD dateModified stop moving on bulk frontmatter edits. baseline_version is the
-- version the next edit is compared against, so small edits accumulate until they cross the threshold. Idempotent.

CREATE TABLE IF NOT EXISTS page_content_dates (
    page_name          TEXT        PRIMARY KEY,
    content_changed_at TIMESTAMPTZ NOT NULL,
    baseline_version   INTEGER     NOT NULL,
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

GRANT SELECT, INSERT, UPDATE, DELETE ON page_content_dates TO :app_user;
