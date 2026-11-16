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
-- V077: work management agent key history. A key that was ever bound to an agent and then unbound (rotation)
-- must never act as its owner (a person) if it survives revocation: actorForApiKey treats a key found here as no actor.
-- Insert-only, like work_event. Requires V076. Idempotent.

CREATE TABLE IF NOT EXISTS work_agent_key_history (
    api_key_id INTEGER PRIMARY KEY REFERENCES api_keys (id),
    agent_id   BIGINT  NOT NULL REFERENCES work_agent (id),
    unbound_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

GRANT SELECT, INSERT ON work_agent_key_history TO :app_user;
