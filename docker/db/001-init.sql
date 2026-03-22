-- PostgreSQL schema for Wikantik User and Group Database
-- This runs automatically on first startup when pgdata volume is empty.
-- The PostgreSQL Docker image creates the user/database from POSTGRES_USER/POSTGRES_DB env vars.

-- Drop existing objects if they exist (safe for fresh installs)
DROP TABLE IF EXISTS group_members;
DROP TABLE IF EXISTS groups;
DROP TABLE IF EXISTS roles;
DROP TABLE IF EXISTS users;

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

-- Insert default admin user
-- Password is 'admin' hashed with {SHA-256} - change immediately after first login!
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
  '{SHA-256}o2tqerJSAYZA6mtgiVYt8DhgA9QnlLFQcIs8RBO785XuY4w4twYOUA==',
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
