-- Container-path database init. Runs once via docker-entrypoint-initdb.d when
-- the pgdata volume is empty. The PostgreSQL image creates the database and
-- user from POSTGRES_DB/POSTGRES_USER; everything else — schema, the single
-- canonical admin seed (admin/admin123, password_must_change=TRUE), grants —
-- comes from bin/db/migrations via migrate.sh, which the wikantik container
-- entrypoint runs before Tomcat starts. Keeping schema out of this file
-- prevents drift between the container and bare-metal install paths.
--
-- pgvector is required by the Knowledge Graph, hybrid retrieval, and Page
-- Graph subsystems; the base image (pgvector/pgvector:pg18) ships the
-- extension, and installing it needs superuser — which only this initdb
-- context has.

CREATE EXTENSION IF NOT EXISTS vector;
