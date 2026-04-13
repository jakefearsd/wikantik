# HSQLDB Removal Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove HSQLDB entirely from wikantik — build, tests, ITs — and replace every test-time database with PostgreSQL 17 + pgvector running in Docker.

**Architecture:** Per-IT-module pgvector container started by `io.fabric8:docker-maven-plugin` in `pre-integration-test`, schema applied by the production `migrate.sh` script via `exec-maven-plugin`, Cargo JNDI DataSource pointed at `jdbc:postgresql://localhost:55432/wikantik`. Unit tests keep using the existing `PostgresTestContainer` singleton. Credentials live in a gitignored `it-db.properties` so no secrets reach the repo.

**Tech Stack:** Maven, `io.fabric8:docker-maven-plugin`, `exec-maven-plugin`, `properties-maven-plugin`, Cargo Tomcat 11, `pgvector/pgvector:pg17`, PostgreSQL 17, Testcontainers, JUnit 5.

---

## Spec

Full spec: `docs/superpowers/specs/2026-04-13-hsqldb-removal-design.md` (the pg16 → pg17 correction was applied inline to match existing `PostgresTestContainer` behavior).

## Execution note

The spec mandates a **big-bang cutover in a single commit**. Do NOT commit between tasks. Every task in Phase 1 through Phase 5 produces local changes. The final task in Phase 6 runs the full verification suite and creates the single commit.

If you hit compilation or test failures mid-plan, fix them in place before moving forward. Do NOT revert work to get to a commitable state early.

---

## File Structure

**Files modified (edits):**

- `pom.xml` — remove HSQLDB dep-mgmt, add `<pgvector.image>` property, drop `<plugin.inmemdb.version>`
- `.gitignore` — add `wikantik-it-tests/it-db.properties`
- `wikantik-it-tests/pom.xml` — delete inmemdb plugin, delete `sso-it` profile, promote sso module, add shared docker+exec+properties plugin config in `pluginManagement`
- `wikantik-it-tests/wikantik-it-test-custom/pom.xml` — drop JDBCPluginIT exclude, activate shared plugins
- `wikantik-it-tests/wikantik-it-test-custom-jdbc/pom.xml` — swap inmemdb plugin for docker/exec/properties
- `wikantik-it-tests/wikantik-it-test-rest/pom.xml` — activate shared plugins
- `wikantik-it-tests/wikantik-it-test-sso/pom.xml` — add PG container alongside mock-oauth2-server
- `wikantik-it-tests/wikantik-selenide-tests/pom.xml` — activate shared plugins
- `wikantik-it-tests/wikantik-selenide-tests/src/main/resources/wikantik-custom.properties` — swap JDBC URL from HSQLDB to PG
- `wikantik-it-tests/wikantik-it-test-sso/src/main/resources/wikantik-custom.properties` — add PG datasource wiring (module already uses XML user/group DB, so only needs datasource for policy grants)
- `wikantik-it-tests/wikantik-selenide-tests/src/main/java/com/wikantik/its/HubOverviewAdminIT.java` — remove `@Disabled`
- `wikantik-it-tests/wikantik-selenide-tests/src/main/java/com/wikantik/its/HubDiscoveryAdminIT.java` — remove `@Disabled`
- `wikantik-it-tests/wikantik-selenide-tests/src/main/java/com/wikantik/its/JDBCPluginIT.java` — update stale javadoc (HSQLDB → PG)
- `wikantik-main/src/test/java/com/wikantik/plugin/JDBCPluginCITest.java` — update stale javadoc
- `wikantik-main/src/test/java/com/wikantik/auth/user/JDBCUserDatabaseTest.java` — update stale javadoc line
- `wikantik-util/src/test/resources/wikantik-custom.properties` — swap HSQLDB JDBC config for PG

**Files created:**

- `wikantik-it-tests/it-db.properties.template` — checked-in credential template
- `wikantik-it-tests/README.md` — documents the Docker prerequisite and the `it-db.properties` bootstrap
- `wikantik-it-tests/src/main/resources/sql/it-test-seed.sql` — test-user + products seed data (replaces the HSQLDB-specific `hsql-userdb-setup.sql`)

**Files deleted:**

- `wikantik-it-tests/wikantik-selenide-tests/src/test/resources/hsqldb/hsql-userdb-setup.sql`
- `wikantik-it-tests/wikantik-selenide-tests/src/test/resources/hsqldb/` (empty directory)

**Files that STAY unchanged even though they mention HSQLDB:**

- `wikantik-main/src/main/java/com/wikantik/plugin/JDBCPlugin.java` — the `DatabaseType.HSQLDB` enum value describes a **runtime-supported dialect** for users who configure JDBCPlugin against their own HSQLDB instance. No HSQLDB JAR dependency at build time — users bring their own driver. Keep.
- `wikantik-main/src/test/java/com/wikantik/plugin/JDBCPluginTest.java` — asserts on `DatabaseType.HSQLDB` enum constants. These are pure-unit assertions on the enum; no HSQLDB runtime needed. Keep.

---

## Phase 1: Credentials bootstrap

### Task 1: Create the IT credentials template and gitignore entry

**Files:**
- Create: `wikantik-it-tests/it-db.properties.template`
- Modify: `.gitignore`

- [ ] **Step 1: Create the template file**

Write `wikantik-it-tests/it-db.properties.template` with:

```properties
# Credentials for the PostgreSQL container spun up by docker-maven-plugin
# during the integration-tests profile. Copied to it-db.properties on first
# build; edit that (gitignored) file if you need different values.
it.db.user=jspwiki
it.db.password=jspwiki-it
```

- [ ] **Step 2: Add the gitignore entry**

In `.gitignore`, add the following line (in the section near the top where other gitignored files like `tomcat/` live; if no such section exists, append at end):

```
wikantik-it-tests/it-db.properties
```

- [ ] **Step 3: Create the real credentials file locally**

Bash:

```bash
cp wikantik-it-tests/it-db.properties.template wikantik-it-tests/it-db.properties
```

- [ ] **Step 4: Verify git ignores the real file**

Bash:

```bash
git check-ignore -v wikantik-it-tests/it-db.properties
```

Expected: output shows the `.gitignore` line matching. If not, fix the gitignore pattern.

---

## Phase 2: Root pom cleanup

### Task 2: Remove HSQLDB from the root pom and add pgvector.image

**Files:**
- Modify: `pom.xml` (lines ~60, ~113, ~574-585)

- [ ] **Step 1: Open the root pom and locate the properties block**

Read `pom.xml` and find the `<properties>` section (around line 60).

- [ ] **Step 2: Replace the `<hsqldb.version>` line with `<pgvector.image>`**

Change:
```xml
    <hsqldb.version>2.7.4</hsqldb.version>
```

to:
```xml
    <pgvector.image>pgvector/pgvector:pg17</pgvector.image>
```

- [ ] **Step 3: Delete the `<plugin.inmemdb.version>` line**

Around line 113, delete the entire line:
```xml
    <plugin.inmemdb.version>1.4.3</plugin.inmemdb.version>
```

- [ ] **Step 4: Delete the `org.hsqldb` dependency-management entries**

Around lines 574-585, delete both `<dependency>` blocks referencing `org.hsqldb` (the one for `hsqldb` and the one for `sqltool`).

- [ ] **Step 5: Delete the inmemdb-maven-plugin pluginManagement entry**

Around line 876, delete the `<plugin>` block for `com.btmatthews.maven.plugins.inmemdb:inmemdb-maven-plugin`.

- [ ] **Step 6: Verify root pom still parses**

Bash:
```bash
mvn -q help:effective-pom -pl . 2>&1 | head -5
```

Expected: no errors. If the command prints an XML parse error, re-read the file and fix the broken tag.

---

## Phase 3: Shared IT pom infrastructure

### Task 3: Rewrite `wikantik-it-tests/pom.xml`

**Files:**
- Modify: `wikantik-it-tests/pom.xml`

This is the largest single edit. Replace the entire file with the content below. Take care to preserve the license header exactly as it appears in the existing file.

- [ ] **Step 1: Overwrite `wikantik-it-tests/pom.xml`**

Full contents (keep the Apache license header at the top):

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!-- APACHE LICENSE HEADER PRESERVED FROM EXISTING FILE -->
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/maven-v4_0_0.xsd">

  <parent>
    <groupId>com.wikantik</groupId>
    <artifactId>wikantik-builder</artifactId>
    <version>1.0.0-SNAPSHOT</version>
  </parent>

  <groupId>com.wikantik.it</groupId>
  <artifactId>wikantik-it-tests</artifactId>
  <modelVersion>4.0.0</modelVersion>
  <description>selenium / cargo integration tests for Wikantik (PostgreSQL + pgvector)</description>
  <packaging>pom</packaging>

  <modules>
    <module>wikantik-selenide-tests</module>
    <module>wikantik-it-test-custom</module>
    <module>wikantik-it-test-custom-jdbc</module>
    <module>wikantik-it-test-rest</module>
    <module>wikantik-it-test-sso</module>
  </modules>

  <properties>
    <!-- Fixed container port: IT modules run sequentially (see CLAUDE.md), so
         collision is not a concern. -->
    <it.db.port>55432</it.db.port>
    <it.db.name>wikantik</it.db.name>
    <it.db.container-alias>wikantik-pg</it.db.container-alias>
    <!-- Relative path from every IT module to the production migrate.sh. -->
    <it.db.migrate-script>${project.basedir}/../../wikantik-war/src/main/config/db/migrate.sh</it.db.migrate-script>
    <it.db.seed-script>${project.basedir}/../src/main/resources/sql/it-test-seed.sql</it.db.seed-script>
  </properties>

  <dependencies>
    <dependency>
      <groupId>com.wikantik</groupId>
      <artifactId>wikantik-war</artifactId>
      <version>${project.version}</version>
      <type>war</type>
    </dependency>

    <dependency>
      <groupId>org.postgresql</groupId>
      <artifactId>postgresql</artifactId>
      <scope>provided</scope>
    </dependency>

    <dependency>
      <groupId>org.junit.jupiter</groupId>
      <artifactId>junit-jupiter-api</artifactId>
      <scope>test</scope>
    </dependency>

    <dependency>
      <groupId>org.junit.jupiter</groupId>
      <artifactId>junit-jupiter-params</artifactId>
      <scope>test</scope>
    </dependency>

    <dependency>
      <groupId>org.junit.jupiter</groupId>
      <artifactId>junit-jupiter-engine</artifactId>
      <scope>test</scope>
    </dependency>

    <dependency>
      <groupId>org.mockito</groupId>
      <artifactId>mockito-core</artifactId>
      <scope>test</scope>
    </dependency>
  </dependencies>

  <profiles>
    <profile>
      <id>integration-tests</id>

      <build>
        <testResources>
          <testResource>
            <directory>${project.basedir}/../wikantik-selenide-tests/src/test/resources</directory>
          </testResource>
        </testResources>

        <resources>
          <resource>
            <directory>${project.basedir}/../wikantik-selenide-tests/src/main/resources</directory>
            <filtering>true</filtering>
          </resource>
        </resources>

        <pluginManagement>
          <plugins>
            <!-- Load it-db.properties (gitignored credentials) into the Maven
                 property space before any plugin tries to use them. -->
            <plugin>
              <groupId>org.codehaus.mojo</groupId>
              <artifactId>properties-maven-plugin</artifactId>
              <version>1.2.1</version>
              <executions>
                <execution>
                  <id>load-it-db-credentials</id>
                  <phase>initialize</phase>
                  <goals><goal>read-project-properties</goal></goals>
                  <configuration>
                    <files>
                      <file>${project.basedir}/../it-db.properties</file>
                    </files>
                  </configuration>
                </execution>
              </executions>
            </plugin>

            <plugin>
              <artifactId>maven-failsafe-plugin</artifactId>
              <version>${plugin.surefire.version}</version>
              <configuration>
                <dependenciesToScan>
                  <dependency>${project.groupId}:wikantik-selenide-tests</dependency>
                </dependenciesToScan>
                <systemPropertyVariables>
                  <it-wikantik.base.url>http://localhost:8080/${it-wikantik.context}</it-wikantik.base.url>
                  <it-wikantik.config.browser-size>1366x768</it-wikantik.config.browser-size>
                  <it-wikantik.config.download-folder>./target/downloads</it-wikantik.config.download-folder>
                  <it-wikantik.config.headless>false</it-wikantik.config.headless>
                  <it-wikantik.config.reports-folder>${project.basedir}/target/selenide</it-wikantik.config.reports-folder>
                  <it-wikantik.config.wdm.target-path>${project.basedir}/target/wdm</it-wikantik.config.wdm.target-path>
                  <it-wikantik.login.janne.username>janne</it-wikantik.login.janne.username>
                  <it-wikantik.login.janne.password>myP@5sw0rd</it-wikantik.login.janne.password>
                </systemPropertyVariables>
              </configuration>
              <executions>
                <execution>
                  <id>run-integration-tests</id>
                  <goals>
                    <goal>integration-test</goal>
                    <goal>verify</goal>
                  </goals>
                </execution>
              </executions>
            </plugin>

            <plugin>
              <artifactId>maven-war-plugin</artifactId>
              <version>${plugin.war.version}</version>
            </plugin>

            <!-- Start the pgvector container before Cargo boots Tomcat, tear
                 it down after. autoRemove + removeVolumes leaves zero residue. -->
            <plugin>
              <groupId>io.fabric8</groupId>
              <artifactId>docker-maven-plugin</artifactId>
              <configuration>
                <images>
                  <image>
                    <alias>${it.db.container-alias}</alias>
                    <name>${pgvector.image}</name>
                    <run>
                      <ports>
                        <port>${it.db.port}:5432</port>
                      </ports>
                      <env>
                        <POSTGRES_USER>${it.db.user}</POSTGRES_USER>
                        <POSTGRES_PASSWORD>${it.db.password}</POSTGRES_PASSWORD>
                        <POSTGRES_DB>${it.db.name}</POSTGRES_DB>
                      </env>
                      <wait>
                        <log>database system is ready to accept connections</log>
                        <time>30000</time>
                      </wait>
                      <autoRemove>true</autoRemove>
                      <removeVolumes>true</removeVolumes>
                    </run>
                  </image>
                </images>
              </configuration>
              <executions>
                <execution>
                  <id>pg-start</id>
                  <phase>pre-integration-test</phase>
                  <goals><goal>start</goal></goals>
                </execution>
                <execution>
                  <id>pg-stop</id>
                  <phase>post-integration-test</phase>
                  <goals><goal>stop</goal></goals>
                </execution>
              </executions>
            </plugin>

            <!-- Apply production migrations + IT seed data after PG is up but
                 before Cargo starts. exec-maven-plugin respects Maven phase
                 ordering within pre-integration-test, and docker-maven-plugin
                 appears earlier, so the container is already healthy. -->
            <plugin>
              <groupId>org.codehaus.mojo</groupId>
              <artifactId>exec-maven-plugin</artifactId>
              <version>3.1.1</version>
              <executions>
                <execution>
                  <id>pg-apply-migrations</id>
                  <phase>pre-integration-test</phase>
                  <goals><goal>exec</goal></goals>
                  <configuration>
                    <executable>bash</executable>
                    <arguments>
                      <argument>${it.db.migrate-script}</argument>
                    </arguments>
                    <environmentVariables>
                      <DB_NAME>${it.db.name}</DB_NAME>
                      <DB_APP_USER>${it.db.user}</DB_APP_USER>
                      <PGHOST>localhost</PGHOST>
                      <PGPORT>${it.db.port}</PGPORT>
                      <PGUSER>${it.db.user}</PGUSER>
                      <PGPASSWORD>${it.db.password}</PGPASSWORD>
                    </environmentVariables>
                  </configuration>
                </execution>
                <execution>
                  <id>pg-seed-test-data</id>
                  <phase>pre-integration-test</phase>
                  <goals><goal>exec</goal></goals>
                  <configuration>
                    <executable>psql</executable>
                    <arguments>
                      <argument>-v</argument>
                      <argument>ON_ERROR_STOP=1</argument>
                      <argument>-h</argument>
                      <argument>localhost</argument>
                      <argument>-p</argument>
                      <argument>${it.db.port}</argument>
                      <argument>-U</argument>
                      <argument>${it.db.user}</argument>
                      <argument>-d</argument>
                      <argument>${it.db.name}</argument>
                      <argument>-f</argument>
                      <argument>${it.db.seed-script}</argument>
                    </arguments>
                    <environmentVariables>
                      <PGPASSWORD>${it.db.password}</PGPASSWORD>
                    </environmentVariables>
                  </configuration>
                </execution>
              </executions>
            </plugin>

            <!-- Tomcat 11 + PG JNDI DataSource wired directly in Cargo config
                 (no ROOT.xml filtering). -->
            <plugin>
              <groupId>org.codehaus.cargo</groupId>
              <artifactId>cargo-maven3-plugin</artifactId>
              <configuration>
                <configuration>
                  <datasources>
                    <datasource>
                      <jndiName>jdbc/WikiDatabase</jndiName>
                      <driverClass>org.postgresql.Driver</driverClass>
                      <url>jdbc:postgresql://localhost:${it.db.port}/${it.db.name}</url>
                      <username>${it.db.user}</username>
                      <password>${it.db.password}</password>
                      <connectionProperties>
                        <maxTotal>10</maxTotal>
                        <maxIdle>5</maxIdle>
                        <maxWaitMillis>5000</maxWaitMillis>
                      </connectionProperties>
                    </datasource>
                  </datasources>
                  <users>
                    <user>
                      <name>admin</name>
                      <password>myP@5sw0rd</password>
                      <roles>
                        <role>Admin</role>
                        <role>Authenticated</role>
                      </roles>
                    </user>
                    <user>
                      <name>janne</name>
                      <password>myP@5sw0rd</password>
                      <roles>
                        <role>Authenticated</role>
                      </roles>
                    </user>
                  </users>
                  <properties>
                    <cargo.jvmargs>-Djava.awt.headless=true -XX:+EnableDynamicAgentLoading</cargo.jvmargs>
                  </properties>
                </configuration>
                <container>
                  <dependencies>
                    <dependency>
                      <groupId>org.postgresql</groupId>
                      <artifactId>postgresql</artifactId>
                      <classpath>shared</classpath>
                    </dependency>
                  </dependencies>
                </container>
              </configuration>
              <executions>
                <execution>
                  <id>container-start</id>
                  <goals><goal>start</goal></goals>
                  <phase>pre-integration-test</phase>
                </execution>
                <execution>
                  <id>container-stop</id>
                  <goals><goal>stop</goal></goals>
                  <phase>post-integration-test</phase>
                  <configuration>
                    <ignoreFailures>true</ignoreFailures>
                  </configuration>
                </execution>
              </executions>
              <dependencies>
                <dependency>
                  <groupId>org.postgresql</groupId>
                  <artifactId>postgresql</artifactId>
                </dependency>
              </dependencies>
            </plugin>
          </plugins>
        </pluginManagement>
      </build>
    </profile>
  </profiles>
</project>
```

- [ ] **Step 2: Verify parent pom parses**

Bash:
```bash
mvn -q help:effective-pom -pl wikantik-it-tests -Pintegration-tests 2>&1 | tail -20
```

Expected: no errors; output ends with `</project>`.

---

### Task 4: Create the IT test seed SQL

**Files:**
- Create: `wikantik-it-tests/src/main/resources/sql/it-test-seed.sql`

This file replaces `hsql-userdb-setup.sql`. The user/role/group tables are now created by migrations (V002); we only seed test-specific rows that production doesn't need.

- [ ] **Step 1: Create the seed file**

Write `wikantik-it-tests/src/main/resources/sql/it-test-seed.sql` with:

```sql
-- Licensed to the Apache Software Foundation (ASF) under one
-- or more contributor license agreements.  See the NOTICE file
-- distributed with this work for additional information
-- regarding copyright ownership.  The ASF licenses this file
-- to you under the Apache License, Version 2.0 (the
-- "License"); you may not use this file except in compliance
-- with the License.  You may obtain a copy of the License at
--
--     http://www.apache.org/licenses/LICENSE-2.0
--
-- Unless required by applicable law or agreed to in writing, software
-- distributed under the License is distributed on an "AS IS" BASIS,
-- WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
-- See the License for the specific language governing permissions and
-- limitations under the License.

-- IT seed data. Runs after migrate.sh has created users/roles/groups/
-- group_members via V002__core_users_groups.sql. Idempotent (ON CONFLICT).

-- -----------------------------------------------------------------------
-- Test users (janne for JDBCPluginIT admin login, plus group fixtures)
-- -----------------------------------------------------------------------
INSERT INTO users (uid, email, full_name, login_name, password, wiki_name, attributes)
VALUES (
  '-7739839977499061014',
  'janne@ecyrd.com',
  'Janne Jalkanen',
  'janne',
  '{SSHA}1WFv9OV11pD5IySgVH3sFa2VlCyYjbLrcVT/qw==',
  'JanneJalkanen',
  'rO0ABXNyABFqYXZhLnV0aWwuSGFzaE1hcAUH2sHDFmDRAwACRgAKbG9hZEZhY3RvckkACXRocmVzaG9sZHhwP0AAAAAAAAF3CAAAAAIAAAABdAAKYXR0cmlidXRlMXQAK3NvbWUgcmFuZG9tIHZhbHVlXG5hdHRyaWJ1dGUyPWFub3RoZXIgdmFsdWV4'
)
ON CONFLICT (login_name) DO NOTHING;

INSERT INTO roles (login_name, role) VALUES ('janne', 'Authenticated')
ON CONFLICT DO NOTHING;

-- Give JanneJalkanen admin group membership so IT suites that need admin
-- can log in as janne. GroupManager.isUserInRole matches by principal name,
-- and after login the session carries the wiki-name WikiPrincipal.
INSERT INTO group_members (name, member) VALUES ('Admin', 'JanneJalkanen')
ON CONFLICT DO NOTHING;

-- -----------------------------------------------------------------------
-- JDBCPluginIT sample data
-- -----------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS products (
  id integer NOT NULL PRIMARY KEY,
  name varchar(100) NOT NULL,
  category varchar(50),
  price decimal(10,2),
  in_stock integer
);

-- Grant DML to the app user so the JDBCPlugin (running as jspwiki) can read it.
GRANT SELECT, INSERT, UPDATE, DELETE ON products TO jspwiki;

TRUNCATE products;
INSERT INTO products (id, name, category, price, in_stock) VALUES
  (1, 'Laptop',       'Electronics', 999.99, 10),
  (2, 'Mouse',        'Electronics',  29.99, 50),
  (3, 'Keyboard',     'Electronics',  79.99, 25),
  (4, 'Office Chair', 'Furniture',   299.99,  8),
  (5, 'Desk Lamp',    'Furniture',    49.99, 30);

-- -----------------------------------------------------------------------
-- Supplementary group fixtures (parity with the old HSQL seed)
-- -----------------------------------------------------------------------
INSERT INTO groups (name, created, modified) VALUES
  ('TV',         NOW(), NOW()),
  ('Literature', NOW(), NOW()),
  ('Art',        NOW(), NOW())
ON CONFLICT DO NOTHING;

INSERT INTO group_members (name, member) VALUES
  ('TV',         'Archie Bunker'),
  ('TV',         'BullwinkleMoose'),
  ('TV',         'Fred Friendly'),
  ('Literature', 'Charles Dickens'),
  ('Literature', 'Homer')
ON CONFLICT DO NOTHING;
```

- [ ] **Step 2: Verify file is syntactically valid SQL**

Bash:
```bash
test -f wikantik-it-tests/src/main/resources/sql/it-test-seed.sql && echo "present"
```

Expected: `present`. SQL gets exercised by the IT run in Phase 6.

---

### Task 5: Delete the old HSQLDB seed file

**Files:**
- Delete: `wikantik-it-tests/wikantik-selenide-tests/src/test/resources/hsqldb/hsql-userdb-setup.sql`
- Delete: `wikantik-it-tests/wikantik-selenide-tests/src/test/resources/hsqldb/` (directory)

- [ ] **Step 1: Remove the file and directory**

Bash:
```bash
git rm wikantik-it-tests/wikantik-selenide-tests/src/test/resources/hsqldb/hsql-userdb-setup.sql
rmdir wikantik-it-tests/wikantik-selenide-tests/src/test/resources/hsqldb
```

- [ ] **Step 2: Verify nothing references the removed path**

Use Grep tool:
```
pattern: hsql-userdb-setup|hsqldb/hsql
```

Expected: no matches (the removed file is gone; the pom.xml rewrite in Task 3 already dropped its `<sourceFile>` reference).

---

## Phase 4: Per-IT-module pom cleanup

### Task 6: `wikantik-it-test-custom-jdbc/pom.xml`

**Files:**
- Modify: `wikantik-it-tests/wikantik-it-test-custom-jdbc/pom.xml`

- [ ] **Step 1: Swap the inmemdb plugin for the inherited docker/exec/properties plugins**

Replace the entire `<build>` block. Before (lines 41-57):

```xml
  <build>
    <plugins>
      <plugin>
        <groupId>com.btmatthews.maven.plugins.inmemdb</groupId>
        <artifactId>inmemdb-maven-plugin</artifactId>
      </plugin>

      <plugin>
        <artifactId>maven-failsafe-plugin</artifactId>
      </plugin>

      <plugin>
        <groupId>org.codehaus.cargo</groupId>
        <artifactId>cargo-maven3-plugin</artifactId>
      </plugin>
    </plugins>
  </build>
```

After:

```xml
  <build>
    <plugins>
      <plugin>
        <groupId>org.codehaus.mojo</groupId>
        <artifactId>properties-maven-plugin</artifactId>
      </plugin>

      <plugin>
        <groupId>io.fabric8</groupId>
        <artifactId>docker-maven-plugin</artifactId>
      </plugin>

      <plugin>
        <groupId>org.codehaus.mojo</groupId>
        <artifactId>exec-maven-plugin</artifactId>
      </plugin>

      <plugin>
        <artifactId>maven-failsafe-plugin</artifactId>
      </plugin>

      <plugin>
        <groupId>org.codehaus.cargo</groupId>
        <artifactId>cargo-maven3-plugin</artifactId>
      </plugin>
    </plugins>
  </build>
```

- [ ] **Step 2: Verify module still parses**

Bash:
```bash
mvn -q help:effective-pom -pl wikantik-it-tests/wikantik-it-test-custom-jdbc -Pintegration-tests -am 2>&1 | tail -5
```

Expected: ends with `</project>`, no errors.

---

### Task 7: `wikantik-it-test-custom/pom.xml`

**Files:**
- Modify: `wikantik-it-tests/wikantik-it-test-custom/pom.xml`

- [ ] **Step 1: Drop the `JDBCPluginIT` exclude and activate shared plugins**

Replace the `<build>` block. Before:

```xml
  <build>
    <plugins>
      <plugin>
        <artifactId>maven-failsafe-plugin</artifactId>
        <configuration>
          <excludes>
            <!-- JDBCPluginIT requires HSQLDB which is not configured in this module -->
            <exclude>**/JDBCPluginIT.java</exclude>
          </excludes>
        </configuration>
      </plugin>

      <plugin>
        <groupId>org.codehaus.cargo</groupId>
        <artifactId>cargo-maven3-plugin</artifactId>
      </plugin>
    </plugins>
  </build>
```

After:

```xml
  <build>
    <plugins>
      <plugin>
        <groupId>org.codehaus.mojo</groupId>
        <artifactId>properties-maven-plugin</artifactId>
      </plugin>

      <plugin>
        <groupId>io.fabric8</groupId>
        <artifactId>docker-maven-plugin</artifactId>
      </plugin>

      <plugin>
        <groupId>org.codehaus.mojo</groupId>
        <artifactId>exec-maven-plugin</artifactId>
      </plugin>

      <plugin>
        <artifactId>maven-failsafe-plugin</artifactId>
      </plugin>

      <plugin>
        <groupId>org.codehaus.cargo</groupId>
        <artifactId>cargo-maven3-plugin</artifactId>
      </plugin>
    </plugins>
  </build>
```

- [ ] **Step 2: Verify module still parses**

Bash:
```bash
mvn -q help:effective-pom -pl wikantik-it-tests/wikantik-it-test-custom -Pintegration-tests -am 2>&1 | tail -5
```

Expected: ends with `</project>`.

---

### Task 8: `wikantik-it-test-rest/pom.xml`

**Files:**
- Modify: `wikantik-it-tests/wikantik-it-test-rest/pom.xml`

- [ ] **Step 1: Add shared plugins to the `<plugins>` list**

Insert the following three plugins at the **top** of the existing `<plugins>` block (before `maven-failsafe-plugin`):

```xml
      <plugin>
        <groupId>org.codehaus.mojo</groupId>
        <artifactId>properties-maven-plugin</artifactId>
      </plugin>

      <plugin>
        <groupId>io.fabric8</groupId>
        <artifactId>docker-maven-plugin</artifactId>
      </plugin>

      <plugin>
        <groupId>org.codehaus.mojo</groupId>
        <artifactId>exec-maven-plugin</artifactId>
      </plugin>
```

Leave the existing `maven-failsafe-plugin` and `cargo-maven3-plugin` blocks intact.

- [ ] **Step 2: Verify module still parses**

Bash:
```bash
mvn -q help:effective-pom -pl wikantik-it-tests/wikantik-it-test-rest -Pintegration-tests -am 2>&1 | tail -5
```

Expected: ends with `</project>`.

---

### Task 9: `wikantik-selenide-tests/pom.xml`

**Files:**
- Modify: `wikantik-it-tests/wikantik-selenide-tests/pom.xml`

- [ ] **Step 1: Read the current pom**

Use the Read tool on `wikantik-it-tests/wikantik-selenide-tests/pom.xml` to identify the existing `<plugins>` block layout.

- [ ] **Step 2: Add the shared plugins**

In the `<plugins>` block, before `maven-failsafe-plugin` (or before `cargo-maven3-plugin` if failsafe is absent), insert:

```xml
      <plugin>
        <groupId>org.codehaus.mojo</groupId>
        <artifactId>properties-maven-plugin</artifactId>
      </plugin>

      <plugin>
        <groupId>io.fabric8</groupId>
        <artifactId>docker-maven-plugin</artifactId>
      </plugin>

      <plugin>
        <groupId>org.codehaus.mojo</groupId>
        <artifactId>exec-maven-plugin</artifactId>
      </plugin>
```

- [ ] **Step 3: Verify module still parses**

Bash:
```bash
mvn -q help:effective-pom -pl wikantik-it-tests/wikantik-selenide-tests -Pintegration-tests -am 2>&1 | tail -5
```

Expected: ends with `</project>`.

---

### Task 10: `wikantik-it-test-sso/pom.xml` — add PG alongside mock-oauth2-server

**Files:**
- Modify: `wikantik-it-tests/wikantik-it-test-sso/pom.xml`

The SSO module already has its own `docker-maven-plugin` block for mock-oauth2-server. It needs to add a second `<image>` for pgvector (the parent pluginManagement provides one image, but child activation overrides it — we need both images in the child).

- [ ] **Step 1: Replace the `docker-maven-plugin` block**

Locate the existing `<plugin>` block for `io.fabric8:docker-maven-plugin` (around lines 82-125). Replace the entire `<images>` subtree with both images:

```xml
          <images>
            <image>
              <alias>${it.db.container-alias}</alias>
              <name>${pgvector.image}</name>
              <run>
                <ports>
                  <port>${it.db.port}:5432</port>
                </ports>
                <env>
                  <POSTGRES_USER>${it.db.user}</POSTGRES_USER>
                  <POSTGRES_PASSWORD>${it.db.password}</POSTGRES_PASSWORD>
                  <POSTGRES_DB>${it.db.name}</POSTGRES_DB>
                </env>
                <wait>
                  <log>database system is ready to accept connections</log>
                  <time>30000</time>
                </wait>
                <autoRemove>true</autoRemove>
                <removeVolumes>true</removeVolumes>
              </run>
            </image>
            <image>
              <alias>mock-oauth2-server</alias>
              <name>${it-wikantik.mock-oauth.image}</name>
              <run>
                <ports>
                  <port>${it-wikantik.mock-oauth.port}:8080</port>
                </ports>
                <wait>
                  <http>
                    <url>http://localhost:${it-wikantik.mock-oauth.port}/default/.well-known/openid-configuration</url>
                    <method>GET</method>
                    <status>200</status>
                  </http>
                  <time>30000</time>
                </wait>
              </run>
            </image>
          </images>
```

- [ ] **Step 2: Replace the execution ids to cover both images**

Change the `<executions>` block of the same plugin to:

```xml
        <executions>
          <execution>
            <id>it-containers-start</id>
            <phase>pre-integration-test</phase>
            <goals><goal>start</goal></goals>
          </execution>
          <execution>
            <id>it-containers-stop</id>
            <phase>post-integration-test</phase>
            <goals><goal>stop</goal></goals>
          </execution>
        </executions>
```

- [ ] **Step 3: Add the `properties-maven-plugin` and `exec-maven-plugin` to the `<plugins>` block**

Insert before the existing `docker-maven-plugin` block:

```xml
      <plugin>
        <groupId>org.codehaus.mojo</groupId>
        <artifactId>properties-maven-plugin</artifactId>
      </plugin>
```

And after the `docker-maven-plugin` block (before `cargo-maven3-plugin`):

```xml
      <plugin>
        <groupId>org.codehaus.mojo</groupId>
        <artifactId>exec-maven-plugin</artifactId>
      </plugin>
```

- [ ] **Step 4: Verify module still parses**

Bash:
```bash
mvn -q help:effective-pom -pl wikantik-it-tests/wikantik-it-test-sso -Pintegration-tests -am 2>&1 | tail -5
```

Expected: ends with `</project>`. The effective pom should show both images configured.

---

## Phase 5: Properties and Java sources

### Task 11: `wikantik-selenide-tests/src/main/resources/wikantik-custom.properties`

**Files:**
- Modify: `wikantik-it-tests/wikantik-selenide-tests/src/main/resources/wikantik-custom.properties`

- [ ] **Step 1: Replace the JDBC block**

Replace lines 66-74 (the `JDBC Plugin configuration` section). Before:

```properties
#
# JDBC Plugin configuration for integration tests
# Uses the same HSQLDB instance as the user/group database
#
jdbc.driver = org.hsqldb.jdbc.JDBCDriver
jdbc.url = jdbc:hsqldb:hsql://localhost/wikantik
jdbc.user = SA
jdbc.password =
jdbc.maxresults = 100
```

After:

```properties
#
# JDBC Plugin configuration for integration tests
# Points at the pgvector IT container (same DB as the JDBC user/group DB)
#
jdbc.driver = org.postgresql.Driver
jdbc.url = jdbc:postgresql://localhost:55432/wikantik
jdbc.user = jspwiki
jdbc.password = jspwiki-it
jdbc.maxresults = 100

#
# Datasource wiring — activates DB-backed policy grants (V003 migration)
#
wikantik.datasource = java:comp/env/jdbc/WikiDatabase
```

**Why the password is inline here:** this file is a test fixture, not a production config. The `it.db.password` value is the well-known test credential defined in the template. It is NOT a secret that GitHub's secret-scanner should flag (no pattern match against known secret formats). If you want the Maven filtering path instead, wrap the values as `${it.db.user}`/`${it.db.password}` and rely on resource filtering — but this file already contains other test passwords (`myP@5sw0rd`), so inline is consistent.

- [ ] **Step 2: Verify**

Use Grep tool:
```
pattern: hsqldb
path: wikantik-it-tests/wikantik-selenide-tests/src/main/resources/wikantik-custom.properties
```

Expected: no matches.

---

### Task 12: `wikantik-it-test-sso` — add datasource wiring

**Files:**
- Modify: `wikantik-it-tests/wikantik-it-test-sso/src/main/resources/wikantik-custom.properties`

The SSO module uses `XMLUserDatabase` + `XMLGroupDatabase` (not JDBC auth), but still needs `wikantik.datasource` set so DB-backed policy grants and knowledge-graph/hub features work against the PG container.

- [ ] **Step 1: Append datasource wiring**

At the end of `wikantik-it-tests/wikantik-it-test-sso/src/main/resources/wikantik-custom.properties`, add:

```properties

#
# --- Datasource wiring ------------------------------------------------------
# Activates DB-backed policy grants (V003) and knowledge-graph tables
# (V004-V007) against the pgvector IT container.
#
wikantik.datasource = java:comp/env/jdbc/WikiDatabase
```

- [ ] **Step 2: Verify**

Use Read tool on the file and confirm the new lines are at the bottom.

---

### Task 13: Enable Hub ITs

**Files:**
- Modify: `wikantik-it-tests/wikantik-selenide-tests/src/main/java/com/wikantik/its/HubOverviewAdminIT.java`
- Modify: `wikantik-it-tests/wikantik-selenide-tests/src/main/java/com/wikantik/its/HubDiscoveryAdminIT.java`

- [ ] **Step 1: Find the `@Disabled` on `HubOverviewAdminIT`**

Use Grep tool:
```
pattern: @Disabled
path: wikantik-it-tests/wikantik-selenide-tests/src/main/java/com/wikantik/its/HubOverviewAdminIT.java
-n: true
```

- [ ] **Step 2: Delete the `@Disabled(...)` annotation line and the preceding javadoc lines that reference the disable reason**

Use Edit tool. Remove the `@Disabled("Requires a PostgreSQL+pgvector datasource; ...")` annotation (typically preceded by a javadoc note). Leave the class body intact.

If there's a multi-line javadoc explaining the disable, remove those javadoc lines too — they're now misleading.

- [ ] **Step 3: Repeat for `HubDiscoveryAdminIT.java`**

Same steps.

- [ ] **Step 4: Verify neither file still disables itself**

Use Grep tool:
```
pattern: @Disabled\b
path: wikantik-it-tests/wikantik-selenide-tests/src/main/java/com/wikantik/its/HubOverviewAdminIT.java
```

And:
```
pattern: @Disabled\b
path: wikantik-it-tests/wikantik-selenide-tests/src/main/java/com/wikantik/its/HubDiscoveryAdminIT.java
```

Expected: matches only on `@DisabledOnOs(OS.WINDOWS)` (legitimate platform skip), no bare `@Disabled`.

---

### Task 14: Clean stale HSQLDB mentions in Java sources

**Files:**
- Modify: `wikantik-main/src/test/java/com/wikantik/plugin/JDBCPluginCITest.java`
- Modify: `wikantik-main/src/test/java/com/wikantik/auth/user/JDBCUserDatabaseTest.java`
- Modify: `wikantik-it-tests/wikantik-selenide-tests/src/main/java/com/wikantik/its/JDBCPluginIT.java`

These are doc-only edits — the test code already runs against Postgres; only the comments are stale.

- [ ] **Step 1: `JDBCPluginCITest.java` — rewrite the javadoc header**

Replace the class javadoc (around lines 49-61). Before:

```java
/**
 * Integration-style tests for {@link JDBCPlugin} that exercise the JDBC execution path
 * using an HSQLDB in-memory database.  These tests cover the ConnectionConfig inner class,
 * SQL result formatting, error-handling paths, and the addResultLimit variants that are
 * not exercised by the pure-unit JDBCPluginTest.
 *
 * <p>HSQLDB is available as a test-scope dependency in wikantik-main.
 * HSQLDB uses the FETCH FIRST limit style, which exercises that code path.</p>
 *
 * <p>A static anchor connection keeps the HSQLDB in-memory database alive for the
 * duration of the test class; HSQLDB drops the in-memory DB when its last connection
 * is closed.</p>
 */
```

After:

```java
/**
 * Integration-style tests for {@link JDBCPlugin} that exercise the JDBC execution path
 * against a real PostgreSQL container (via {@link com.wikantik.PostgresTestContainer}).
 * Covers the ConnectionConfig inner class, SQL result formatting, error-handling
 * paths, and the addResultLimit variants not exercised by the pure-unit JDBCPluginTest.
 */
```

- [ ] **Step 2: Fix inline HSQLDB comments in `JDBCPluginCITest.java`**

Use Grep tool to find them:
```
pattern: HSQLDB
path: wikantik-main/src/test/java/com/wikantik/plugin/JDBCPluginCITest.java
-n: true
```

Edit each remaining mention:
- Line ~113 `Happy path: executes a real SELECT against HSQLDB` → `Happy path: executes a real SELECT against PostgreSQL`
- Line ~303 `// ============== addResultLimit coverage (HSQLDB = FETCH FIRST style) ==============` → `// ============== addResultLimit coverage (PostgreSQL = LIMIT style) ==============`
- Line ~306 `HSQLDB uses FETCH FIRST n ROWS ONLY.` — rewrite the block to describe PostgreSQL's `LIMIT` behavior, matching the actual assertions in the test body (if the test body actually asserts FETCH-FIRST on HSQLDB, it must be re-examined — read the test body at that line range and decide whether it's still correct against PG's LIMIT, or whether the assertion belongs in the pure-unit `JDBCPluginTest` alongside the enum assertions. If the assertion only verifies the FETCH-FIRST enum constants, move the assertion into `JDBCPluginTest`).
- Lines 441-462 are enum-level assertions (`DatabaseType.HSQLDB.fromDriver(...)`). These remain valid — the enum still has an HSQLDB value as a runtime-supported dialect. Keep the assertions; only update surrounding comments if they describe runtime behavior against HSQLDB (they shouldn't — they describe the enum).

- [ ] **Step 3: `JDBCUserDatabaseTest.java` — fix stale comment**

Grep for the HSQLDB reference:
```
pattern: HSQLDB
path: wikantik-main/src/test/java/com/wikantik/auth/user/JDBCUserDatabaseTest.java
-n: true
```

Line ~463: `// isn't properly persisted in HSQLDB test environment)` → `// isn't properly persisted in the test container)` (or delete the parenthetical if it's a leftover from a comment that no longer applies).

- [ ] **Step 4: `JDBCPluginIT.java` — refresh the javadoc**

Replace the class javadoc (lines 36-47). Before:

```java
/**
 * Integration tests for the JDBCPlugin.
 * Tests that the plugin can execute SQL queries against an HSQLDB database
 * and render the results as HTML tables.
 *
 * <p>The JDBC plugin requires {@code AllPermission} (admin) to run arbitrary
 * SQL, so the suite authenticates as janne once per class — the HSQL seed
 * grants {@code JanneJalkanen} membership in the {@code Admin} group in the
 * IT test environment, which in turn grants {@code AllPermission} via the
 * default {@code wikantik.policy}. Logging in per-test would fail because the
 * React SPA preserves the auth cookie across tests in the same class.
 */
```

After:

```java
/**
 * Integration tests for the JDBCPlugin.
 * Tests that the plugin can execute SQL queries against the IT PostgreSQL
 * container and render the results as HTML tables.
 *
 * <p>The JDBC plugin requires {@code AllPermission} (admin) to run arbitrary
 * SQL, so the suite authenticates as janne once per class — the IT seed
 * ({@code it-test-seed.sql}) grants {@code JanneJalkanen} membership in the
 * {@code Admin} group, which in turn grants {@code AllPermission} via the
 * default {@code wikantik.policy}. Logging in per-test would fail because the
 * React SPA preserves the auth cookie across tests in the same class.
 */
```

- [ ] **Step 5: Verify no misleading comments remain**

Use Grep tool:
```
pattern: HSQLDB|hsqldb in-memory|HSQL seed
path: wikantik-main/src/test/java/com/wikantik/
-n: true
```

Plus:
```
pattern: HSQLDB|hsqldb
path: wikantik-it-tests/wikantik-selenide-tests/src/main/java/
-n: true
```

Expected: the only surviving references are:
- Enum value `DatabaseType.HSQLDB` (in `JDBCPlugin.java` and the `JDBCPluginTest.java` assertions on that enum) — these are runtime-dialect metadata, not test-time HSQLDB usage.

No other HSQLDB mentions should remain in `wikantik-it-tests/` or in `wikantik-main/src/test/`.

---

### Task 15: `wikantik-util/src/test/resources/wikantik-custom.properties` — swap HSQLDB block for PG

**Files:**
- Modify: `wikantik-util/src/test/resources/wikantik-custom.properties`

- [ ] **Step 1: Identify the HSQLDB block**

Read lines 80-96 of the file. The block contains `server.port`, `server.database.0`, `server.dbname.0`, `jdbc.admin.*`, `jdbc.driver.*`, `jdbc.user.*`.

- [ ] **Step 2: Determine whether any test consumes these properties**

Use Grep tool:
```
pattern: server\.database\.0|server\.dbname\.0|jdbc\.admin\.id|jdbc\.driver\.id|jdbc\.driver\.url
path: wikantik-util/src/test
```

If no test reads any of these keys, delete the entire `# for JDBC tests` block (lines 84-95).

If tests do read them, replace the block with PG equivalents:

```properties
# for JDBC tests — overridden at runtime by PostgresTestContainer
jdbc.admin.id=test
jdbc.admin.password=test
jdbc.driver.class=org.postgresql.Driver
jdbc.driver.id=postgres
jdbc.driver.url=jdbc:postgresql://localhost:5432/wikantik_test
jdbc.user.id=wikantik
jdbc.user.password=password
```

- [ ] **Step 3: Verify no HSQLDB strings remain**

Grep tool:
```
pattern: hsqldb
path: wikantik-util/src/test/resources/wikantik-custom.properties
-i: true
```

Expected: no matches.

---

## Phase 6: Documentation, verification, commit

### Task 16: Write the IT README

**Files:**
- Create: `wikantik-it-tests/README.md`

- [ ] **Step 1: Create the README**

Write `wikantik-it-tests/README.md` with:

```markdown
# Integration Tests

The `integration-tests` Maven profile boots a Tomcat 11 instance (via Cargo)
plus a PostgreSQL 17 + pgvector container (via
[`io.fabric8:docker-maven-plugin`](https://dmp.fabric8.io/)) for every IT
submodule. Schema is applied by the production `migrate.sh` script, and
`src/main/resources/sql/it-test-seed.sql` adds the test fixtures.

## Prerequisites

- **Docker daemon** running locally
- **`psql`** on the `PATH` (used by the seed step)
- JDK 21+, Maven 3.9+
- A gitignored `it-db.properties` file — copy from the template on first run:
  ```
  cp wikantik-it-tests/it-db.properties.template wikantik-it-tests/it-db.properties
  ```

## Running

```bash
# All IT modules, including SSO and Hub pgvector tests, run under one profile.
mvn clean install -Pintegration-tests -fae
```

IT modules must run sequentially — **do not** pass `-T` with the
`integration-tests` profile. Port 55432 is reused across modules.

## Troubleshooting

- **Port 55432 bound:** stop any local PG listening on that port, or change
  `<it.db.port>` in `wikantik-it-tests/pom.xml`.
- **`psql: command not found`:** install `postgresql-client` (e.g.
  `sudo apt install postgresql-client` on Debian/Ubuntu).
- **Container left running after `Ctrl-C`:** `autoRemove=true` should clean
  it up, but if something leaks, `docker ps | grep wikantik-pg` and
  `docker stop <id>`.
```

---

### Task 17: Final source scrub

- [ ] **Step 1: Grep the repo for HSQLDB residue**

Use Grep tool:
```
pattern: hsqldb
path: .
-i: true
```

Expected hits (allowed):
- `docs/superpowers/specs/2026-04-13-hsqldb-removal-design.md` — the spec
- `docs/superpowers/plans/2026-04-14-hsqldb-removal.md` — this plan
- `wikantik-main/src/main/java/com/wikantik/plugin/JDBCPlugin.java` — `DatabaseType.HSQLDB` enum value (runtime dialect support)
- `wikantik-main/src/test/java/com/wikantik/plugin/JDBCPluginTest.java` — enum-value assertions
- `docs/wikantik-pages/News.md`, `docs/PostgreSQLLocalDeployment.md`, `temp/PostgreSQLLocalDeployment.md`, `docs/wikantik-pages/PostgreSQLLocalDeployment.md`, `ReleaseNotes`, `ChangeLog.md`, `LICENSE`, `wikantik-war/src/main/config/dev/OldChangeLog`, `docs/superpowers/plans/2026-04-05-datasource-consolidation.md`, `docs/superpowers/plans/2026-03-28-database-backed-permissions.md` — historical docs; do not touch unless a line is actively misleading about the current state.

Everything else in the output is a bug — go fix it.

- [ ] **Step 2: Grep Maven dependency tree for HSQLDB**

Bash:
```bash
mvn -q dependency:tree -Pintegration-tests 2>&1 | grep -i hsqldb || echo "CLEAN"
```

Expected: `CLEAN`.

---

### Task 18: Verify unit tests still pass

- [ ] **Step 1: Run unit tests**

Bash:
```bash
mvn clean install -DskipITs -T 1C
```

Expected: `BUILD SUCCESS`. All unit tests pass, including `JDBCPluginTest`, `JDBCPluginCITest`, `JDBCUserDatabaseTest`.

If anything fails: investigate in place. Do NOT work around by skipping tests.

---

### Task 19: Verify integration tests pass

- [ ] **Step 1: Ensure Docker is running**

Bash:
```bash
docker ps >/dev/null 2>&1 && echo "docker OK" || echo "docker NOT running — start it first"
```

- [ ] **Step 2: Run the full IT suite**

Bash:
```bash
mvn clean install -Pintegration-tests -fae
```

Expected: `BUILD SUCCESS` across all 5 IT modules — `wikantik-selenide-tests`, `wikantik-it-test-custom`, `wikantik-it-test-custom-jdbc`, `wikantik-it-test-rest`, `wikantik-it-test-sso`. The previously-`@Disabled` `HubOverviewAdminIT` and `HubDiscoveryAdminIT` now run and pass.

If a module fails, read its `target/failsafe-reports/` output to diagnose. Fix in place.

---

### Task 20: Commit

- [ ] **Step 1: Stage changes explicitly**

Per CLAUDE.md, never use `git add -A`. Stage each file by name.

Bash:
```bash
git add \
  pom.xml \
  .gitignore \
  wikantik-it-tests/pom.xml \
  wikantik-it-tests/it-db.properties.template \
  wikantik-it-tests/README.md \
  wikantik-it-tests/src/main/resources/sql/it-test-seed.sql \
  wikantik-it-tests/wikantik-it-test-custom/pom.xml \
  wikantik-it-tests/wikantik-it-test-custom-jdbc/pom.xml \
  wikantik-it-tests/wikantik-it-test-rest/pom.xml \
  wikantik-it-tests/wikantik-it-test-sso/pom.xml \
  wikantik-it-tests/wikantik-it-test-sso/src/main/resources/wikantik-custom.properties \
  wikantik-it-tests/wikantik-selenide-tests/pom.xml \
  wikantik-it-tests/wikantik-selenide-tests/src/main/resources/wikantik-custom.properties \
  wikantik-it-tests/wikantik-selenide-tests/src/main/java/com/wikantik/its/HubOverviewAdminIT.java \
  wikantik-it-tests/wikantik-selenide-tests/src/main/java/com/wikantik/its/HubDiscoveryAdminIT.java \
  wikantik-it-tests/wikantik-selenide-tests/src/main/java/com/wikantik/its/JDBCPluginIT.java \
  wikantik-main/src/test/java/com/wikantik/plugin/JDBCPluginCITest.java \
  wikantik-main/src/test/java/com/wikantik/auth/user/JDBCUserDatabaseTest.java \
  wikantik-util/src/test/resources/wikantik-custom.properties \
  docs/superpowers/specs/2026-04-13-hsqldb-removal-design.md \
  docs/superpowers/plans/2026-04-14-hsqldb-removal.md
```

Stage the deleted seed file:

```bash
git rm wikantik-it-tests/wikantik-selenide-tests/src/test/resources/hsqldb/hsql-userdb-setup.sql 2>/dev/null || true
```

- [ ] **Step 2: Sanity-check staging**

Bash:
```bash
git status
```

Expected: every file listed above is staged. `it-db.properties` is NOT staged (gitignored).

- [ ] **Step 3: Create the commit**

```bash
git commit -m "$(cat <<'EOF'
refactor(tests): drop HSQLDB entirely; PostgreSQL+pgvector everywhere

Every IT now boots a pgvector/pgvector:pg17 container via
docker-maven-plugin and applies migrations with the production migrate.sh
script. SSO, Hub, and pgvector ITs all run under the default
integration-tests profile — no more sso-it opt-in. Credentials live in a
gitignored it-db.properties file.

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>
EOF
)"
```

- [ ] **Step 4: Verify the commit landed**

Bash:
```bash
git log -1 --stat
```

Expected: the commit summary matches, file list covers all staged changes.

---

## Self-Review Notes

- **Spec coverage:** every spec section maps to tasks 1-20. Profile consolidation → Task 3. Credential hygiene → Task 1. Migration via `migrate.sh` → Task 3. Per-module containers → Tasks 3, 6-10. Hub IT re-enable → Task 13. Unit-test HSQLDB cleanup → Task 15. Final scrub → Task 17.
- **Placeholder scan:** no TBDs; every code block shows the actual content; every Bash command is runnable; every Grep query names the exact pattern.
- **Type consistency:** property names are consistent across tasks (`it.db.port`, `it.db.user`, `it.db.password`, `it.db.name`, `it.db.container-alias`, `it.db.migrate-script`, `it.db.seed-script`, `pgvector.image`). Plugin group/artifact IDs match across declarations.
- **Known risk (spec):** Cargo's filtering of `ROOT.xml` — the plan sidesteps this entirely by putting the PG datasource config directly in `cargo-maven3-plugin` XML, no `ROOT.xml` file needed. This matches the existing `sso-it` pattern.

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-04-14-hsqldb-removal.md`. Two execution options:

**1. Subagent-Driven (recommended)** — I dispatch a fresh subagent per task, review between tasks, fast iteration.

**2. Inline Execution** — Execute tasks in this session using executing-plans, batch execution with checkpoints.

Which approach?
