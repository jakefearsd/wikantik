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

-- V080: an agent is no longer bound to an API key (work management phase 3a).
--
-- Agents authenticate with their own credential (work_agent_credential, V079). The binding column and the
-- table that remembered which API keys had ever been an agent's are dropped: no API key is, was or can
-- become an agent. A personal key that was once bound becomes an ordinary personal key again, so retire
-- phase 2 agents (or revoke their keys) before applying this where any exist.
--
-- Idempotent. DDL only.

ALTER TABLE work_agent DROP COLUMN IF EXISTS api_key_id;
DROP TABLE IF EXISTS work_agent_key_history;
