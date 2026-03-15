---
type: article
tags:
- uncategorized
summary: PostgreSQL Local Deployment Guide
---
1. PostgreSQL Local Deployment Guide

This guide walks you through setting up JSPWiki to use PostgreSQL for user and group storage, deployed to a local Tomcat 11 instance for manual testing.

  1. Overview

This setup involves:
- **Tomcat 11** at `tomcat/tomcat-11/` (gitignored)
- **PostgreSQL** running locally with the `jspwiki` user configured
- **Template files** in `jspwiki-war/src/main/config/tomcat/` (git-tracked)
- **Deployment script** `deploy-local.sh` automates the deployment process

The strategy is to create template configuration files in the tracked codebase, which the deployment script copies to your Tomcat instance. This:
- Keeps sensitive passwords out of git
- Documents the expected configuration
- Has zero impact on the existing test suite (which uses HSQLDB via Cargo)
- Survives WAR rebuilds (only needs password edit once)

  1. Prerequisites

- PostgreSQL 15+ installed and running
- Database `jspwiki` created: `CREATE DATABASE jspwiki;`
- Java 17+ installed
- Maven 3.9+ installed
- Tomcat 11 installed at `tomcat/tomcat-11/`

---

  1. Quick Start

If you've already completed the one-time setup, the deployment cycle is:

```bash
1. 1. Build the project
mvn clean install -Dmaven.test.skip

1. 2. Deploy to local Tomcat
./deploy-local.sh

1. 3. Start Tomcat
tomcat/tomcat-11/bin/startup.sh

1. 4. Access the wiki
1. http://localhost:8080/JSPWiki/
```

---

  1. One-Time Setup

    1. Step 1: Initialize the PostgreSQL Database

Run the DDL script to set up tables and the default admin user:

```bash
1. Run as postgres superuser
sudo -u postgres psql -d jspwiki -f jspwiki-war/src/main/config/db/postgresql.ddl
```

This creates:
- `users`, `roles`, `groups`, `group_members` tables
- Default `admin` user with password `admin` (SSHA hashed)
- Default `Admin` role and group

  - Important**: Change the admin password immediately after first login!

    1. Step 2: Build JSPWiki

```bash
1. Full build with tests
mvn clean install

1. Or skip tests for faster iteration
mvn clean install -Dmaven.test.skip
```

    1. Step 3: Run the Deployment Script

```bash
./deploy-local.sh
```

The script will:
1. Check that the WAR file exists
2. Download the PostgreSQL JDBC driver (if not present)
3. Create configuration directories
4. Copy template files (if they don't already exist)
5. Stop Tomcat (if running)
6. Deploy the WAR file

    1. Step 4: Configure the Database Password

Edit the context file to set your PostgreSQL password:

```bash
nano tomcat/tomcat-11/conf/Catalina/localhost/JSPWiki.xml
```

Replace `YOUR_SECURE_PASSWORD_HERE` with your actual password for the `jspwiki` database user (appears twice in the file).

    1. Step 5: Start Tomcat

```bash
tomcat/tomcat-11/bin/startup.sh

1. Watch the logs for any errors
tail -f tomcat/tomcat-11/logs/catalina.out
```

    1. Step 6: Test the Deployment

1. Open http://localhost:8080/JSPWiki/
2. Login with: `admin` / `admin`
3. Verify the database connection by checking PostgreSQL:

```bash
sudo -u postgres psql -d jspwiki -c "SELECT login_name, email FROM users;"
```

---

  1. Subsequent Deployments

After the one-time setup, redeploying is simple:

```bash
1. Rebuild
mvn clean install -Dmaven.test.skip

1. Redeploy (does not overwrite your password configuration)
./deploy-local.sh

1. Restart Tomcat
tomcat/tomcat-11/bin/shutdown.sh
tomcat/tomcat-11/bin/startup.sh
```

The deployment script preserves existing configuration files, so your password settings survive redeployments.

---

  1. What the Deployment Script Does

The `deploy-local.sh` script automates these tasks:

| Task | Details |
|------|---------|
| WAR check | Verifies `jspwiki-war/target/JSPWiki.war` exists |
| JDBC driver | Downloads PostgreSQL driver to `tomcat/lib/` if missing |
| Context file | Copies template to `conf/Catalina/localhost/JSPWiki.xml` if missing |
| Properties | Copies template to `lib/jspwiki-custom.properties` if missing |
| Stop Tomcat | Gracefully stops Tomcat if running |
| Clean deploy | Removes old `webapps/JSPWiki/` directory |
| Deploy WAR | Copies new WAR to `webapps/` |
| Password check | Warns if password placeholder still present |

---

  1. Configuration Files

    1. Git-Tracked Templates

Located in `jspwiki-war/src/main/config/tomcat/`:

| File | Purpose |
|------|---------|
| `JSPWiki-context.xml.template` | JNDI DataSource configuration for PostgreSQL |
| `jspwiki-custom-postgresql.properties.template` | JSPWiki JDBC database settings |

    1. Local Files (Not in Git)

Located in `tomcat/tomcat-11/`:

| File | Purpose |
|------|---------|
| `lib/postgresql-42.7.4.jar` | PostgreSQL JDBC driver |
| `lib/jspwiki-custom.properties` | Customized properties (paths, settings) |
| `conf/Catalina/localhost/JSPWiki.xml` | Customized context (contains password) |

---

  1. Troubleshooting

    1. Common Issues

| Symptom | Cause | Solution |
|---------|-------|----------|
| `Cannot create JDBC driver` | PostgreSQL JAR not found | Run `./deploy-local.sh` to download driver |
| `JNDI name not found` | Context file not loaded | Check `JSPWiki.xml` is in `conf/Catalina/localhost/` |
| `Password authentication failed` | Wrong DB password | Update password in `JSPWiki.xml` |
| `Connection refused` | PostgreSQL not running | Start PostgreSQL: `sudo systemctl start postgresql` |
| Login fails with correct password | Wrong password hash format | Regenerate password using `CryptoUtil` |
| WAR file not found | Build not run | Run `mvn clean install` first |

    1. Checking Logs

```bash
1. Tomcat logs
tail -f tomcat/tomcat-11/logs/catalina.out

1. Look for JNDI errors
grep -i "jdbc\|datasource\|jndi" tomcat/tomcat-11/logs/catalina.out

1. PostgreSQL logs (location varies by installation)
sudo tail -f /var/log/postgresql/postgresql-*-main.log
```

    1. Resetting Configuration

To start fresh with configuration files:

```bash
1. Remove local configuration (will be recreated by deploy-local.sh)
rm tomcat/tomcat-11/conf/Catalina/localhost/JSPWiki.xml
rm tomcat/tomcat-11/lib/jspwiki-custom.properties

1. Redeploy
./deploy-local.sh

1. Don't forget to set your password again!
nano tomcat/tomcat-11/conf/Catalina/localhost/JSPWiki.xml
```

    1. Resetting the Database

To reset the database to a clean state:

```bash
1. Re-run the DDL (drops and recreates all tables)
sudo -u postgres psql -d jspwiki -f jspwiki-war/src/main/config/db/postgresql.ddl
```

---

  1. Impact on Test Suite

This configuration has **zero impact** on the existing test suite:

- **Unit tests** use `TestEngine` with their own configuration
- **Integration tests** use Cargo plugin + HSQLDB
- The `tomcat/` directory is gitignored
- The `deploy-local.sh` script is for manual testing only

You can run `mvn clean test` or `mvn clean install` without any PostgreSQL-related changes affecting the build.

---

  1. Related Documentation

- [Developing with PostgreSQL](DevelopingWithPostgresql.md) - Detailed JDBC configuration reference
- [PostgreSQL DDL](../jspwiki-war/src/main/config/db/postgresql.ddl) - Database schema
- [HSQLDB DDL](../jspwiki-war/src/main/config/db/hsql.ddl) - Reference schema for comparison
