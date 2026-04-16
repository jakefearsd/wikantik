-- Licensed under the Apache License, Version 2.0 (the "License").
-- Migration: schema_migrations tracking table
--
-- Establishes the tracking table used by migrate.sh to determine which
-- migrations have already been applied to a database. This migration is
-- always run first; it is idempotent and self-registering.

CREATE TABLE IF NOT EXISTS schema_migrations (
    version    VARCHAR(64) PRIMARY KEY,
    applied_at TIMESTAMP   NOT NULL DEFAULT NOW()
);

-- The application user needs read access so the wiki can report its
-- own migration state (e.g. from a health check).
GRANT SELECT ON schema_migrations TO :app_user;
