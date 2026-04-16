-- Licensed under the Apache License, Version 2.0 (the "License").
-- Migration: core user, role, and group tables
--
-- Creates the tables used by JDBCUserDatabase and JDBCGroupDatabase, plus
-- seeds the default admin account if no rows exist yet. Fully idempotent:
-- running this migration against a database that already has these tables
-- is a no-op.
--
-- Prerequisite: the application user referenced by :app_user must already
-- exist (install-fresh.sh creates it; production already has it).

CREATE TABLE IF NOT EXISTS users (
    uid         VARCHAR(100),
    email       VARCHAR(100),
    full_name   VARCHAR(100),
    login_name  VARCHAR(100) NOT NULL PRIMARY KEY,
    password    VARCHAR(100),
    wiki_name   VARCHAR(100),
    created     TIMESTAMP,
    modified    TIMESTAMP,
    lock_expiry TIMESTAMP,
    bio         VARCHAR(1000),
    attributes  TEXT
);

-- Columns added over time — ADD COLUMN IF NOT EXISTS keeps this migration
-- correct whether the table was created by this migration or by the older
-- postgresql.ddl baseline.
ALTER TABLE users ADD COLUMN IF NOT EXISTS lock_expiry TIMESTAMP;
ALTER TABLE users ADD COLUMN IF NOT EXISTS bio         VARCHAR(1000);
ALTER TABLE users ADD COLUMN IF NOT EXISTS attributes  TEXT;

CREATE TABLE IF NOT EXISTS roles (
    login_name VARCHAR(100) NOT NULL,
    role       VARCHAR(100) NOT NULL
);

CREATE TABLE IF NOT EXISTS groups (
    name     VARCHAR(100) NOT NULL PRIMARY KEY,
    creator  VARCHAR(100),
    created  TIMESTAMP,
    modifier VARCHAR(100),
    modified TIMESTAMP
);

CREATE TABLE IF NOT EXISTS group_members (
    name   VARCHAR(100) NOT NULL,
    member VARCHAR(100) NOT NULL,
    CONSTRAINT group_members_pk PRIMARY KEY (name, member)
);

-- Application user grants (idempotent — GRANT is a no-op if already held).
GRANT SELECT, INSERT, UPDATE, DELETE ON users         TO :app_user;
GRANT SELECT, INSERT, UPDATE, DELETE ON roles         TO :app_user;
GRANT SELECT, INSERT, UPDATE, DELETE ON groups        TO :app_user;
GRANT SELECT, INSERT, UPDATE, DELETE ON group_members TO :app_user;

-- Seed the default admin account only if the users table is empty.
-- This protects existing databases from having their admin record overwritten.
INSERT INTO users (uid, email, full_name, login_name, password, wiki_name)
SELECT '-6852820166199419346',
       'admin@localhost',
       'Administrator',
       'admin',
       '{SHA-256}zij+qwD6JAlf9h+1tHdjNWTgqDASCJxt4y70G64C9PIoxFO2Lyq/xg==',
       'Administrator'
WHERE NOT EXISTS (SELECT 1 FROM users);

INSERT INTO roles (login_name, role)
SELECT 'admin', 'Admin'
WHERE NOT EXISTS (SELECT 1 FROM roles WHERE login_name = 'admin' AND role = 'Admin');

INSERT INTO groups (name, created, modified)
SELECT 'Admin', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM groups WHERE name = 'Admin');

INSERT INTO group_members (name, member)
SELECT 'Admin', 'admin'
WHERE NOT EXISTS (SELECT 1 FROM group_members WHERE name = 'Admin' AND member = 'admin');
