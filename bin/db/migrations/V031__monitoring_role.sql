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

-- V031: Create a dedicated, least-privilege monitoring role for
-- postgres_exporter. Membership in the built-in pg_monitor role grants full
-- pg_stat_* visibility without superuser. Used only by the opt-in
-- observability overlay; the application never connects as this role.
--
-- Idempotent: the role is created only if absent; the grant is a no-op when
-- already held. The role is created NOLOGIN and only gains LOGIN when a
-- password is supplied via the psql variable :exporter_password (threaded
-- from $DB_EXPORTER_PASSWORD by migrate.sh), so it can never be a passwordless
-- login role. No secret is committed. When the variable is empty the password
-- step is skipped, leaving the role NOLOGIN (and any existing password
-- untouched).

DO $$
BEGIN
    IF NOT EXISTS ( SELECT 1 FROM pg_roles WHERE rolname = 'wikantik_exporter' ) THEN
        CREATE ROLE wikantik_exporter NOLOGIN;
    END IF;
END
$$;

GRANT pg_monitor TO wikantik_exporter;

SELECT :'exporter_password' <> '' AS have_exporter_password \gset
\if :have_exporter_password
ALTER ROLE wikantik_exporter LOGIN PASSWORD :'exporter_password';
\endif
