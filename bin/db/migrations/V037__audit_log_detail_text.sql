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

-- V037: Change audit_log.detail from JSONB to TEXT to preserve exact round-trip.
-- PostgreSQL JSONB reformats JSON on storage (e.g. {"source":"scim"} -> {"source": "scim"}),
-- which breaks the tamper-evident hash chain: the hash is computed over the raw string at
-- write time, but verifyChain() reads the reformatted value — they no longer match.
-- detail is metadata-only and is never queried as JSON, so TEXT gives identical semantics
-- with exact round-trip fidelity. Idempotent: no-op if already TEXT.
DO $$
BEGIN
  IF EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_name = 'audit_log'
      AND column_name = 'detail'
      AND data_type = 'jsonb'
  ) THEN
    ALTER TABLE audit_log ALTER COLUMN detail TYPE TEXT USING detail::text;
  END IF;
END $$;
