/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

-- PostgreSQL 15+ compatible DDL for JSPWiki User and Group Database
-- Tested with PostgreSQL 18
--
-- IMPORTANT: This script must be run as a PostgreSQL superuser (e.g., 'postgres')
-- because it creates a database user and grants permissions.
--
-- Usage:
--   sudo -u postgres psql -d jspwiki -f postgresql.ddl
--
-- Prerequisites:
--   1. Create the database first: CREATE DATABASE jspwiki;
--   2. Run this script as superuser to create tables and the application user

-- Drop existing objects if they exist (safe for fresh installs)
DROP TABLE IF EXISTS group_members;
DROP TABLE IF EXISTS groups;
DROP TABLE IF EXISTS roles;
DROP TABLE IF EXISTS users;

-- Revoke privileges before dropping user (required if user exists with grants)
REVOKE ALL PRIVILEGES ON ALL TABLES IN SCHEMA public FROM jspwiki;
REVOKE ALL PRIVILEGES ON SCHEMA public FROM jspwiki;
REVOKE ALL PRIVILEGES ON DATABASE jspwiki FROM jspwiki;

-- Drop and recreate application user (requires superuser privileges)
DROP USER IF EXISTS jspwiki;

-- Users table: stores user profiles
CREATE TABLE users (
  uid VARCHAR(100),
  email VARCHAR(100),
  full_name VARCHAR(100),
  login_name VARCHAR(100) NOT NULL PRIMARY KEY,
  password VARCHAR(100),
  wiki_name VARCHAR(100),
  created TIMESTAMP,
  modified TIMESTAMP,
  lock_expiry TIMESTAMP,
  attributes TEXT
);

-- Roles table: stores user roles for container-managed authentication
CREATE TABLE roles (
  login_name VARCHAR(100) NOT NULL,
  role VARCHAR(100) NOT NULL
);

-- Groups table: stores wiki group definitions
CREATE TABLE groups (
  name VARCHAR(100) NOT NULL PRIMARY KEY,
  creator VARCHAR(100),
  created TIMESTAMP,
  modifier VARCHAR(100),
  modified TIMESTAMP
);

-- Group members table: stores group membership
CREATE TABLE group_members (
  name VARCHAR(100) NOT NULL,
  member VARCHAR(100) NOT NULL,
  CONSTRAINT group_members_pk
    PRIMARY KEY (name, member)
);

-- Create application user
-- NOTE: Change 'password' to a secure password before running in production!
CREATE USER jspwiki WITH ENCRYPTED PASSWORD 'password' NOCREATEDB NOCREATEROLE;

-- Grant table permissions to the application user
GRANT SELECT, INSERT, UPDATE, DELETE ON users TO jspwiki;
GRANT SELECT, INSERT, UPDATE, DELETE ON roles TO jspwiki;
GRANT SELECT, INSERT, UPDATE, DELETE ON groups TO jspwiki;
GRANT SELECT, INSERT, UPDATE, DELETE ON group_members TO jspwiki;

-- Insert default admin user
-- Password is 'admin' hashed with {SSHA} - change immediately after first login!
INSERT INTO users (
  uid,
  email,
  full_name,
  login_name,
  password,
  wiki_name
) VALUES (
  '-6852820166199419346',
  'admin@localhost',
  'Administrator',
  'admin',
  '{SSHA}6YNKYMwXICUf5pMvYUZumgbFCxZMT2njtUQtJw==',
  'Administrator'
);

-- Assign Admin role to the admin user
INSERT INTO roles (
  login_name,
  role
) VALUES (
  'admin',
  'Admin'
);

-- Create default Admin group
INSERT INTO groups (
  name,
  created,
  modified
) VALUES (
  'Admin',
  '2006-06-20 14:50:54.00000000',
  '2006-06-20 14:50:54.00000000'
);

-- Add Administrator to Admin group
INSERT INTO group_members (
  name,
  member
) VALUES (
  'Admin',
  'Administrator'
);

-- Cleanup commands (uncomment to use)
-- DROP TABLE IF EXISTS group_members;
-- DROP TABLE IF EXISTS groups;
-- DROP TABLE IF EXISTS roles;
-- DROP TABLE IF EXISTS users;
-- DROP USER IF EXISTS jspwiki;
