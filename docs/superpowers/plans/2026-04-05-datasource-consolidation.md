# DataSource Consolidation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace 3 identical JNDI connection pools and 4 property names with a single `wikantik.datasource` property and `jdbc/WikiDatabase` pool.

**Architecture:** Add `PROP_DATASOURCE` and `DEFAULT_DATASOURCE` constants to `AbstractJDBCDatabase`. Update all subsystems (user DB, group DB, authorization, knowledge graph, observability) to read the single property. Remove the file-based policy fallback — database policy is now always active. Update config templates, default properties, test resources, and documentation.

**Tech Stack:** Java 21, Maven, JNDI DataSource, PostgreSQL, H2 (tests)

---

## File Structure

| File | Responsibility |
|------|---------------|
| `wikantik-main/.../auth/AbstractJDBCDatabase.java` | Add shared `PROP_DATASOURCE` + `DEFAULT_DATASOURCE` constants |
| `wikantik-main/.../auth/user/JDBCUserDatabase.java` | Remove old constants, use parent's |
| `wikantik-main/.../auth/authorize/JDBCGroupDatabase.java` | Remove old constants, use parent's |
| `wikantik-main/.../auth/DefaultAuthorizationManager.java` | Remove `PROP_POLICY_DATASOURCE`, always use database policy |
| `wikantik-main/.../WikiEngine.java` | Read `wikantik.datasource` for knowledge graph |
| `wikantik-observability/.../ObservabilityLifecycleExtension.java` | Read `wikantik.datasource` |
| `wikantik-war/.../Wikantik-context.xml.template` | Single `jdbc/WikiDatabase` resource |
| `wikantik-war/.../wikantik-custom-postgresql.properties.template` | Single `wikantik.datasource` property |
| `wikantik-main/src/main/resources/ini/wikantik.properties` | Replace two datasource properties with one |
| `wikantik-main/src/test/resources/ini/wikantik.properties` | Same |
| `wikantik-util/src/test/resources/ini/wikantik.properties` | Same |
| `wikantik-main/src/test/resources/WEB-INF/web.xml` | Single `jdbc/WikiDatabase` resource-ref |
| `wikantik-main/src/test/.../JDBCUserDatabaseTest.java` | Bind `jdbc/WikiDatabase` instead of `jdbc/UserDatabase` |
| `wikantik-main/src/test/.../JDBCGroupDatabaseTest.java` | Bind `jdbc/WikiDatabase` instead of `jdbc/GroupDatabase` |
| `wikantik-observability/src/test/.../ObservabilityLifecycleExtensionTest.java` | Use `wikantik.datasource` |
| `wikantik-it-tests/pom.xml` | Single Cargo JNDI resource |
| Documentation files (8 files) | Update property/JNDI references |

---

### Task 1: Add shared constants to AbstractJDBCDatabase

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/auth/AbstractJDBCDatabase.java:42-44`

- [ ] **Step 1: Add PROP_DATASOURCE and DEFAULT_DATASOURCE constants**

Add immediately after the class declaration (line 42), before the LOG field:

```java
    /** Property name for the single shared JNDI DataSource. */
    public static final String PROP_DATASOURCE = "wikantik.datasource";

    /** Default JNDI name for the shared DataSource. */
    public static final String DEFAULT_DATASOURCE = "jdbc/WikiDatabase";
```

- [ ] **Step 2: Compile-check the module**

Run: `mvn compile -pl wikantik-main -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/auth/AbstractJDBCDatabase.java
git commit -m "feat: add PROP_DATASOURCE and DEFAULT_DATASOURCE to AbstractJDBCDatabase"
```

---

### Task 2: Update JDBCUserDatabase to use shared constants

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/auth/user/JDBCUserDatabase.java:204,234,424,497`
- Modify: `wikantik-main/src/test/java/com/wikantik/auth/user/JDBCUserDatabaseTest.java:102`

- [ ] **Step 1: Remove old constants and update initialize()**

In `JDBCUserDatabase.java`:

1. **Remove** the constant at line 204:
   ```java
   public static final String DEFAULT_DB_JNDI_NAME = "jdbc/UserDatabase";
   ```

2. **Remove** the constant at line 234:
   ```java
   public static final String PROP_DB_DATASOURCE = "wikantik.userdatabase.datasource";
   ```

3. **Update** line 424 from:
   ```java
   final String jndiName = props.getProperty( PROP_DB_DATASOURCE, DEFAULT_DB_JNDI_NAME );
   ```
   to:
   ```java
   final String jndiName = props.getProperty( PROP_DATASOURCE, DEFAULT_DATASOURCE );
   ```

4. **Update** the error message at line 497 from:
   ```java
   throw new NoRequiredPropertyException( PROP_DB_DATASOURCE, "JDBCUserDatabase initialization error: " + e.getMessage() );
   ```
   to:
   ```java
   throw new NoRequiredPropertyException( PROP_DATASOURCE, "JDBCUserDatabase initialization error: " + e.getMessage() );
   ```

- [ ] **Step 2: Update JDBCUserDatabaseTest JNDI binding**

In `JDBCUserDatabaseTest.java`, change line 102 from:
```java
ctx.bind( JDBCUserDatabase.DEFAULT_DB_JNDI_NAME, m_ds );
```
to:
```java
ctx.bind( AbstractJDBCDatabase.DEFAULT_DATASOURCE, m_ds );
```

Add import if not present:
```java
import com.wikantik.auth.AbstractJDBCDatabase;
```

- [ ] **Step 3: Run JDBCUserDatabaseTest**

Run: `mvn test -pl wikantik-main -Dtest=JDBCUserDatabaseTest -q`
Expected: All tests pass

- [ ] **Step 4: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/auth/user/JDBCUserDatabase.java \
       wikantik-main/src/test/java/com/wikantik/auth/user/JDBCUserDatabaseTest.java
git commit -m "refactor: JDBCUserDatabase uses shared wikantik.datasource property"
```

---

### Task 3: Update JDBCGroupDatabase to use shared constants

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/auth/authorize/JDBCGroupDatabase.java:141,168,411,447`
- Modify: `wikantik-main/src/test/java/com/wikantik/auth/authorize/JDBCGroupDatabaseTest.java:77`

- [ ] **Step 1: Remove old constants and update initialize()**

In `JDBCGroupDatabase.java`:

1. **Remove** the constant at line 141:
   ```java
   public static final String DEFAULT_GROUPDB_DATASOURCE = "jdbc/GroupDatabase";
   ```

2. **Remove** the constant at line 168:
   ```java
   public static final String PROP_GROUPDB_DATASOURCE = "wikantik.groupdatabase.datasource";
   ```

3. **Update** line 411 from:
   ```java
   final String jndiName = props.getProperty( PROP_GROUPDB_DATASOURCE, DEFAULT_GROUPDB_DATASOURCE );
   ```
   to:
   ```java
   final String jndiName = props.getProperty( PROP_DATASOURCE, DEFAULT_DATASOURCE );
   ```

4. **Update** the error message at line 447 from:
   ```java
   throw new NoRequiredPropertyException( PROP_GROUPDB_DATASOURCE, "JDBCGroupDatabase initialization error: " + e);
   ```
   to:
   ```java
   throw new NoRequiredPropertyException( PROP_DATASOURCE, "JDBCGroupDatabase initialization error: " + e);
   ```

- [ ] **Step 2: Update JDBCGroupDatabaseTest JNDI binding**

In `JDBCGroupDatabaseTest.java`, change line 77 from:
```java
ctx.bind( JDBCGroupDatabase.DEFAULT_GROUPDB_DATASOURCE, m_ds );
```
to:
```java
ctx.bind( AbstractJDBCDatabase.DEFAULT_DATASOURCE, m_ds );
```

Add import if not present:
```java
import com.wikantik.auth.AbstractJDBCDatabase;
```

- [ ] **Step 3: Run JDBCGroupDatabaseTest**

Run: `mvn test -pl wikantik-main -Dtest=JDBCGroupDatabaseTest -q`
Expected: All tests pass

- [ ] **Step 4: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/auth/authorize/JDBCGroupDatabase.java \
       wikantik-main/src/test/java/com/wikantik/auth/authorize/JDBCGroupDatabaseTest.java
git commit -m "refactor: JDBCGroupDatabase uses shared wikantik.datasource property"
```

---

### Task 4: Update DefaultAuthorizationManager — always use database policy

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/auth/DefaultAuthorizationManager.java:83,290-320`

- [ ] **Step 1: Remove PROP_POLICY_DATASOURCE and simplify policy init**

In `DefaultAuthorizationManager.java`:

1. **Remove** the constant at line 83:
   ```java
   public static final String PROP_POLICY_DATASOURCE = "wikantik.policy.datasource";
   ```

2. **Replace** lines 290-320 (the policy initialization block). The old code reads `PROP_POLICY_DATASOURCE` and has an if/else for database vs file-based. Replace the entire block starting from `// Initialize security policy` through the end of the else branch with:

   ```java
        // Initialize database-backed security policy
        final String datasource = properties.getProperty( AbstractJDBCDatabase.PROP_DATASOURCE,
                AbstractJDBCDatabase.DEFAULT_DATASOURCE );
        try {
            final String tableName = properties.getProperty( PROP_POLICY_TABLE, DEFAULT_POLICY_TABLE );
            final javax.naming.Context initCtx = new javax.naming.InitialContext();
            final javax.naming.Context ctx = (javax.naming.Context) initCtx.lookup( "java:comp/env" );
            final DataSource policyDs = (DataSource) ctx.lookup( datasource );
            databasePolicy = new DatabasePolicy( policyDs, tableName );
            LOG.info( "Initialized database-backed security policy from JNDI DataSource: {}", datasource );
        } catch ( final Exception e ) {
            LOG.error( "Could not initialize database security policy: {}", e.getMessage() );
            throw new WikiException( "Could not initialize database security policy: " + e.getMessage(), e );
        }
   ```

   This removes the file-based policy fallback entirely. The `localPolicy` field and the `POLICY`/`DEFAULT_POLICY` constants can remain (they're used elsewhere or harmless), but the else branch is deleted.

3. Add import if not present:
   ```java
   import com.wikantik.auth.AbstractJDBCDatabase;
   ```

- [ ] **Step 2: Compile-check**

Run: `mvn compile -pl wikantik-main -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/auth/DefaultAuthorizationManager.java
git commit -m "refactor: DefaultAuthorizationManager always uses database policy via wikantik.datasource"
```

---

### Task 5: Update WikiEngine.initKnowledgeGraph()

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/WikiEngine.java:513-518`

- [ ] **Step 1: Read wikantik.datasource instead of wikantik.knowledge.datasource**

In `WikiEngine.java`, replace lines 513-518:

```java
    private void initKnowledgeGraph( final Properties props ) {
        final String datasource = props.getProperty( "wikantik.knowledge.datasource" );
        if( datasource == null || datasource.isBlank() ) {
            LOG.info( "Knowledge graph disabled (no wikantik.knowledge.datasource configured)" );
            return;
        }
```

with:

```java
    private void initKnowledgeGraph( final Properties props ) {
        final String datasource = props.getProperty( AbstractJDBCDatabase.PROP_DATASOURCE,
                AbstractJDBCDatabase.DEFAULT_DATASOURCE );
```

This removes the early return — when `wikantik.datasource` is configured (which it always is by default), the knowledge graph initializes. The `if` guard and LOG message are removed.

Add import if not present:
```java
import com.wikantik.auth.AbstractJDBCDatabase;
```

- [ ] **Step 2: Compile-check**

Run: `mvn compile -pl wikantik-main -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/WikiEngine.java
git commit -m "refactor: WikiEngine.initKnowledgeGraph reads wikantik.datasource"
```

---

### Task 6: Update ObservabilityLifecycleExtension

**Files:**
- Modify: `wikantik-observability/src/main/java/com/wikantik/observability/ObservabilityLifecycleExtension.java:56-57`
- Modify: `wikantik-observability/src/test/java/com/wikantik/observability/ObservabilityLifecycleExtensionTest.java:75`

- [ ] **Step 1: Update constants**

In `ObservabilityLifecycleExtension.java`, replace lines 56-57:

```java
    private static final String PROP_DB_DATASOURCE = "wikantik.userdatabase.datasource";
    private static final String DEFAULT_DB_DATASOURCE = "jdbc/UserDatabase";
```

with:

```java
    private static final String PROP_DB_DATASOURCE = "wikantik.datasource";
    private static final String DEFAULT_DB_DATASOURCE = "jdbc/WikiDatabase";
```

- [ ] **Step 2: Update ObservabilityLifecycleExtensionTest**

In `ObservabilityLifecycleExtensionTest.java`, change line 75 from:

```java
        props.setProperty( "wikantik.userdatabase.datasource", "jdbc/CustomDB" );
```

to:

```java
        props.setProperty( "wikantik.datasource", "jdbc/CustomDB" );
```

- [ ] **Step 3: Run test**

Run: `mvn test -pl wikantik-observability -Dtest=ObservabilityLifecycleExtensionTest -q`
Expected: All tests pass

- [ ] **Step 4: Commit**

```bash
git add wikantik-observability/src/main/java/com/wikantik/observability/ObservabilityLifecycleExtension.java \
       wikantik-observability/src/test/java/com/wikantik/observability/ObservabilityLifecycleExtensionTest.java
git commit -m "refactor: ObservabilityLifecycleExtension reads wikantik.datasource"
```

---

### Task 7: Update config templates

**Files:**
- Modify: `wikantik-war/src/main/config/tomcat/Wikantik-context.xml.template`
- Modify: `wikantik-war/src/main/config/tomcat/wikantik-custom-postgresql.properties.template`

- [ ] **Step 1: Replace 3 Resource blocks with single jdbc/WikiDatabase**

Replace the entire content of `Wikantik-context.xml.template` between `<Context>` and `</Context>` (lines 28-74) with:

```xml
<Context reloadable="true">

    <!-- Single shared JNDI DataSource for all Wikantik subsystems -->
    <Resource name="jdbc/WikiDatabase"
              auth="Container"
              type="javax.sql.DataSource"
              factory="org.apache.tomcat.dbcp.dbcp2.BasicDataSourceFactory"
              driverClassName="org.postgresql.Driver"
              url="jdbc:postgresql://localhost:5432/wikantik"
              username="wikantik"
              password="YOUR_SECURE_PASSWORD_HERE"
              maxTotal="30"
              maxIdle="10"
              maxWaitMillis="10000"
              validationQuery="SELECT 1"
              testOnBorrow="true"/>

</Context>
```

- [ ] **Step 2: Update properties template**

In `wikantik-custom-postgresql.properties.template`:

1. Replace lines 42-44:
   ```properties
   # JNDI DataSource names (must match context.xml Resource names)
   wikantik.userdatabase.datasource = jdbc/UserDatabase
   wikantik.groupdatabase.datasource = jdbc/GroupDatabase
   ```
   with:
   ```properties
   # JNDI DataSource name (must match context.xml Resource name)
   wikantik.datasource = jdbc/WikiDatabase
   ```

2. Remove lines 71-75 (the policy datasource block):
   ```properties
   # Database-backed security policy (replaces wikantik.policy file)
   # Set this to use the same JNDI DataSource as the wiki database.
   # When set, policy grants are read from the policy_grants table.
   # When not set, the file-based wikantik.policy is used (legacy behavior).
   wikantik.policy.datasource = jdbc/GroupDatabase
   ```

3. Remove lines 81-82 (the knowledge datasource):
   ```properties
   # Knowledge graph datasource (same JNDI DataSource, enables knowledge MCP server)
   wikantik.knowledge.datasource = jdbc/KnowledgeDatabase
   ```

- [ ] **Step 3: Commit**

```bash
git add wikantik-war/src/main/config/tomcat/Wikantik-context.xml.template \
       wikantik-war/src/main/config/tomcat/wikantik-custom-postgresql.properties.template
git commit -m "refactor: config templates use single jdbc/WikiDatabase datasource"
```

---

### Task 8: Update default and test properties files

**Files:**
- Modify: `wikantik-main/src/main/resources/ini/wikantik.properties:909,923`
- Modify: `wikantik-main/src/test/resources/ini/wikantik.properties:74,88`
- Modify: `wikantik-util/src/test/resources/ini/wikantik.properties:76,90`

- [ ] **Step 1: Update main default properties**

In `wikantik-main/src/main/resources/ini/wikantik.properties`, replace lines 909 and 923:

```properties
wikantik.userdatabase.datasource=jdbc/UserDatabase
```
and
```properties
wikantik.groupdatabase.datasource=jdbc/GroupDatabase
```

with a single line (replacing the first occurrence, removing the second):

```properties
wikantik.datasource=jdbc/WikiDatabase
```

- [ ] **Step 2: Update wikantik-main test properties**

In `wikantik-main/src/test/resources/ini/wikantik.properties`, replace lines 74 and 88:

```properties
wikantik.userdatabase.datasource=jdbc/UserDatabase
```
and
```properties
wikantik.groupdatabase.datasource=jdbc/GroupDatabase
```

with a single line (replacing the first occurrence, removing the second):

```properties
wikantik.datasource=jdbc/WikiDatabase
```

- [ ] **Step 3: Update wikantik-util test properties**

In `wikantik-util/src/test/resources/ini/wikantik.properties`, replace lines 76 and 90:

```properties
wikantik.userdatabase.datasource=jdbc/UserDatabase
```
and
```properties
wikantik.groupdatabase.datasource=jdbc/GroupDatabase
```

with a single line (replacing the first occurrence, removing the second):

```properties
wikantik.datasource=jdbc/WikiDatabase
```

- [ ] **Step 4: Commit**

```bash
git add wikantik-main/src/main/resources/ini/wikantik.properties \
       wikantik-main/src/test/resources/ini/wikantik.properties \
       wikantik-util/src/test/resources/ini/wikantik.properties
git commit -m "refactor: properties files use single wikantik.datasource"
```

---

### Task 9: Update test web.xml and integration test pom.xml

**Files:**
- Modify: `wikantik-main/src/test/resources/WEB-INF/web.xml:91-118`
- Modify: `wikantik-it-tests/pom.xml:188-213`

- [ ] **Step 1: Update test web.xml resource-refs**

In `wikantik-main/src/test/resources/WEB-INF/web.xml`, replace lines 91-118 (the two `<resource-ref>` blocks for `jdbc/UserDatabase` and `jdbc/GroupDatabase`) with a single block:

```xml
   <resource-ref>
       <description>
           Resource reference to JNDI factory for the Wikantik database.
       </description>
       <res-ref-name>
           jdbc/WikiDatabase
       </res-ref-name>
       <res-type>
           javax.sql.DataSource
       </res-type>
       <res-auth>
           Container
       </res-auth>
   </resource-ref>
```

- [ ] **Step 2: Update integration test pom.xml Cargo datasources**

In `wikantik-it-tests/pom.xml`, replace lines 188-213 (the two `<datasource>` blocks for `jdbc/GroupDatabase` and `jdbc/UserDatabase`) with a single block:

```xml
                  <datasources>
                    <datasource>
                      <jndiName>jdbc/WikiDatabase</jndiName>
                      <driverClass>org.hsqldb.jdbc.JDBCDriver</driverClass>
                      <url>jdbc:hsqldb:hsql://localhost/wikantik</url>
                      <username>SA</username>
                      <password />
                      <connectionProperties>
                        <maxTotal>10</maxTotal>
                        <maxIdle>5</maxIdle>
                        <maxWaitMillis>5000</maxWaitMillis>
                      </connectionProperties>
                    </datasource>
                  </datasources>
```

- [ ] **Step 3: Commit**

```bash
git add wikantik-main/src/test/resources/WEB-INF/web.xml \
       wikantik-it-tests/pom.xml
git commit -m "refactor: test web.xml and IT pom use single jdbc/WikiDatabase"
```

---

### Task 10: Update documentation

**Files:**
- Modify: `CLAUDE.md:226`
- Modify: `docs/RelationalUserDatabase.md` (lines 34,40,54,60,84,85,175)
- Modify: `docs/DevelopingWithPostgresql.md` (lines 465,497,537,553,606,607,650,657,697,699,875,995)
- Modify: `docs/migration-1.0-to-1.1.md` (lines 17,93)
- Modify: `docs/wikantik-pages/RelationalUserDatabase.md` (lines 40,46,60,66,90,91)
- Modify: `docs/wikantik-pages/DevelopingWithPostgresql.md` (lines 471,503,543,559,612,613,656,663,703,705,881,1001)
- Modify: `docs/wikantik-pages/WikantikOnDocker.md` (line 104)
- Modify: `docs/wikantik-pages/DatabaseBackedPermissions.md` (line 28)

- [ ] **Step 1: Update CLAUDE.md**

Replace line 226:
```
- Property-driven policy switch: set `wikantik.policy.datasource` to use database; omit to fall back to file-based `wikantik.policy`
```
with:
```
- Database-backed policy grants — always active when `wikantik.datasource` is configured (the default)
```

- [ ] **Step 2: Update docs/RelationalUserDatabase.md**

Throughout the file:
- Replace all `jdbc/UserDatabase` with `jdbc/WikiDatabase`
- Replace all `jdbc/GroupDatabase` with `jdbc/WikiDatabase`
- Replace `jspwiki.jdbc.user.jndiname = jdbc/UserDatabase` with `wikantik.datasource = jdbc/WikiDatabase`
- Replace `jspwiki.jdbc.group.jndiname = jdbc/GroupDatabase` — remove line (consolidated above)
- Replace `wikantik.policy.datasource = jdbc/UserDatabase` with a note that policy is always active
- Where there are two `<Resource>` blocks (one for User, one for Group), consolidate to a single `jdbc/WikiDatabase` block with `maxTotal="30"`

- [ ] **Step 3: Update docs/DevelopingWithPostgresql.md**

Throughout the file:
- Replace all `jdbc/UserDatabase` with `jdbc/WikiDatabase`
- Replace all `jdbc/GroupDatabase` with `jdbc/WikiDatabase`
- Replace `jspwiki.userdatabase.datasource = jdbc/UserDatabase` with `wikantik.datasource = jdbc/WikiDatabase`
- Remove `jspwiki.groupdatabase.datasource = jdbc/GroupDatabase` lines
- Where there are two `<Resource>` blocks, consolidate to single `jdbc/WikiDatabase`
- Where there are two `<resource-ref>` blocks, consolidate to single `jdbc/WikiDatabase`
- Update log output examples (`JDBCUserDatabase initialized from JNDI DataSource: jdbc/UserDatabase` → `jdbc/WikiDatabase`)
- Update error message example (`Name [jdbc/UserDatabase] is not bound` → `Name [jdbc/WikiDatabase] is not bound`)

- [ ] **Step 4: Update docs/migration-1.0-to-1.1.md**

Replace both occurrences of:
```
wikantik.policy.datasource = jdbc/GroupDatabase
```
with:
```
wikantik.datasource = jdbc/WikiDatabase
```

- [ ] **Step 5: Update docs/wikantik-pages/ versions**

Apply the same changes as Steps 2-3 to:
- `docs/wikantik-pages/RelationalUserDatabase.md`
- `docs/wikantik-pages/DevelopingWithPostgresql.md`

- [ ] **Step 6: Update docs/wikantik-pages/WikantikOnDocker.md**

Replace line 104:
```
2. **`ROOT.xml`** — Tomcat context with two JNDI DataSources (`jdbc/UserDatabase` and `jdbc/GroupDatabase`) pointing to the PostgreSQL container
```
with:
```
2. **`ROOT.xml`** — Tomcat context with JNDI DataSource (`jdbc/WikiDatabase`) pointing to the PostgreSQL container
```

- [ ] **Step 7: Update docs/wikantik-pages/DatabaseBackedPermissions.md**

Replace line 28:
```
The `postgresql-permissions.ddl` script creates the `policy_grants` and `groups`/`group_members` tables. A property switch (`wikantik.policy.datasource`) controls whether the engine reads permissions from the database or falls back to the XML file.
```
with:
```
The `postgresql-permissions.ddl` script creates the `policy_grants` and `groups`/`group_members` tables. The engine reads permissions from the database using the shared `wikantik.datasource` connection pool.
```

- [ ] **Step 8: Commit**

```bash
git add CLAUDE.md \
       docs/RelationalUserDatabase.md \
       docs/DevelopingWithPostgresql.md \
       docs/migration-1.0-to-1.1.md \
       docs/wikantik-pages/RelationalUserDatabase.md \
       docs/wikantik-pages/DevelopingWithPostgresql.md \
       docs/wikantik-pages/WikantikOnDocker.md \
       docs/wikantik-pages/DatabaseBackedPermissions.md
git commit -m "docs: update all references to use single wikantik.datasource"
```

---

### Task 11: Full build verification

- [ ] **Step 1: Run full build with unit tests**

Run: `mvn clean install -T 1C -DskipITs`
Expected: BUILD SUCCESS with all unit tests passing

- [ ] **Step 2: Fix any failures**

If any tests fail, fix them and re-run.

- [ ] **Step 3: Final commit (if any fixes needed)**

Only if step 2 required changes.
