# Hardcode JDBC SQL Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove all property-driven SQL construction from JDBCUserDatabase and JDBCGroupDatabase, replacing with static final SQL constants and inline column-name strings.

**Architecture:** Delete ~44 constants and ~20 instance fields from the two JDBC database classes. Replace dynamically-built SQL strings with static final constants. Inline column name literals in ResultSet calls. Simplify `initialize()` to just JNDI lookup + connection test + transaction detection. Remove column-mapping properties from all config/properties files.

**Tech Stack:** Java 21, JDBC, JNDI

---

### Task 1: Hardcode JDBCUserDatabase SQL

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/auth/user/JDBCUserDatabase.java`
- Test: `wikantik-main/src/test/java/com/wikantik/auth/user/JDBCUserDatabaseTest.java` (unchanged — verify passes)

- [ ] **Step 1: Replace constants and instance fields with static SQL constants**

In `JDBCUserDatabase.java`, delete everything between `private static final String NOTHING = "";` (line 193) and `private DataSource ds;` (line 251). That is, delete all 28 `DEFAULT_DB_*` and `PROP_DB_*` constants.

Then delete the SQL instance fields (lines 253-279):
```java
    private String deleteUserByLoginName;
    private String deleteRoleByLoginName;
    private String findByEmail;
    private String findByFullName;
    private String findByLoginName;
    private String findByUid;
    private String findByWikiName;
    private String renameProfile;
    private String renameRoles;
    private String updateProfile;
    private String findAll;
    private String findRoles;
    private String insertProfile;
    private String insertRole;
```

Then delete the column-name instance fields (lines 281-301):
```java
    private String attributes;
    private String email;
    private String fullName;
    private String lockExpiry;
    private String loginName;
    private String password;
    private String uid;
    private String wikiName;
    private String bio;
    private String created;
    private String modified;
```

Replace all of the above with these static final SQL constants (place them right after `private static final String NOTHING = "";`):

```java
    private static final String FIND_ALL = "SELECT * FROM users";
    private static final String FIND_BY_EMAIL = "SELECT * FROM users WHERE email=?";
    private static final String FIND_BY_FULL_NAME = "SELECT * FROM users WHERE full_name=?";
    private static final String FIND_BY_LOGIN_NAME = "SELECT * FROM users WHERE login_name=?";
    private static final String FIND_BY_UID = "SELECT * FROM users WHERE uid=?";
    private static final String FIND_BY_WIKI_NAME = "SELECT * FROM users WHERE wiki_name=?";
    private static final String INSERT_PROFILE = "INSERT INTO users (uid,email,full_name,password,wiki_name,modified,login_name,attributes,bio,created) VALUES (?,?,?,?,?,?,?,?,?,?)";
    private static final String UPDATE_PROFILE = "UPDATE users SET uid=?,email=?,full_name=?,password=?,wiki_name=?,modified=?,login_name=?,attributes=?,bio=?,lock_expiry=? WHERE login_name=?";
    private static final String INSERT_ROLE = "INSERT INTO roles (login_name,role) VALUES (?,?)";
    private static final String FIND_ROLES = "SELECT * FROM roles WHERE login_name=?";
    private static final String DELETE_USER = "DELETE FROM users WHERE login_name=?";
    private static final String DELETE_ROLES = "DELETE FROM roles WHERE login_name=?";
    private static final String RENAME_PROFILE = "UPDATE users SET login_name=?,modified=? WHERE login_name=?";
    private static final String RENAME_ROLES = "UPDATE roles SET login_name=? WHERE login_name=?";

    private DataSource ds;
    private boolean supportsCommits;
```

- [ ] **Step 2: Simplify initialize()**

Replace the entire `initialize()` method body (lines 420-516) with:

```java
    @Override
    public void initialize( final Engine engine, final Properties props ) throws NoRequiredPropertyException, WikiSecurityException {
        final String jndiName = props.getProperty( AbstractJDBCDatabase.PROP_DATASOURCE, AbstractJDBCDatabase.DEFAULT_DATASOURCE );
        try {
            final Context initCtx = new InitialContext();
            final Context ctx = (Context) initCtx.lookup( "java:comp/env" );
            ds = (DataSource) ctx.lookup( jndiName );
        } catch( final NamingException e ) {
            LOG.error( "JDBCUserDatabase initialization error: {}", e.getMessage() );
            throw new NoRequiredPropertyException( AbstractJDBCDatabase.PROP_DATASOURCE, "JDBCUserDatabase initialization error: " + e.getMessage() );
        }

        // Test connection
        try( final Connection conn = ds.getConnection(); final PreparedStatement ps = conn.prepareStatement( FIND_ALL ) ) {
        } catch( final SQLException e ) {
            LOG.error( "DB connectivity error: {}", e.getMessage() );
            throw new WikiSecurityException("DB connectivity error: " + e.getMessage(), e );
        }
        LOG.info( "JDBCUserDatabase initialized from JNDI DataSource: {}", jndiName );

        // Determine if the datasource supports commits
        try( final Connection conn = ds.getConnection() ) {
            final DatabaseMetaData dmd = conn.getMetaData();
            if( dmd.supportsTransactions() ) {
                supportsCommits = true;
                conn.setAutoCommit( false );
                LOG.info( "JDBCUserDatabase supports transactions. Good; we will use them." );
            }
        } catch( final SQLException e ) {
            LOG.warn( "JDBCUserDatabase warning: user database doesn't seem to support transactions. Reason: {}", e.getMessage() );
        }
    }
```

- [ ] **Step 3: Update all method references to use new constant names**

Throughout the class, rename all SQL field references to the new static constant names:

| Old reference | New reference |
|---|---|
| `deleteUserByLoginName` | `DELETE_USER` |
| `deleteRoleByLoginName` | `DELETE_ROLES` |
| `findByEmail` (the field, not the method) | `FIND_BY_EMAIL` |
| `findByFullName` (the field) | `FIND_BY_FULL_NAME` |
| `findByLoginName` (the field) | `FIND_BY_LOGIN_NAME` |
| `findByUid` (the field) | `FIND_BY_UID` |
| `findByWikiName` (the field) | `FIND_BY_WIKI_NAME` |
| `findAll` | `FIND_ALL` |
| `findRoles` | `FIND_ROLES` |
| `insertProfile` | `INSERT_PROFILE` |
| `insertRole` | `INSERT_ROLE` |
| `updateProfile` | `UPDATE_PROFILE` |
| `renameProfile` | `RENAME_PROFILE` |
| `renameRoles` | `RENAME_ROLES` |

Be careful: the methods `findByEmail()`, `findByFullName()`, etc. call `findByPreparedStatement()` passing the field. The field has the same name as the method. The references to update are inside those methods, e.g.:
- `return findByPreparedStatement( findByEmail, index );` → `return findByPreparedStatement( FIND_BY_EMAIL, index );`
- `return findByPreparedStatement( findByFullName, index );` → `return findByPreparedStatement( FIND_BY_FULL_NAME, index );`
- `return findByPreparedStatement( findByLoginName, index );` → `return findByPreparedStatement( FIND_BY_LOGIN_NAME, index );`
- `return findByPreparedStatement( findByUid, uidToFind );` → `return findByPreparedStatement( FIND_BY_UID, uidToFind );`
- `return findByPreparedStatement( findByWikiName, index );` → `return findByPreparedStatement( FIND_BY_WIKI_NAME, index );`

In `deleteByLoginName()`:
- `conn.prepareStatement( deleteUserByLoginName )` → `conn.prepareStatement( DELETE_USER )`
- `conn.prepareStatement( deleteRoleByLoginName )` → `conn.prepareStatement( DELETE_ROLES )`

In `getWikiNames()`:
- `conn.prepareStatement( findAll )` → `conn.prepareStatement( FIND_ALL )`

In `rename()`:
- `conn.prepareStatement( renameProfile )` → `conn.prepareStatement( RENAME_PROFILE )`
- `conn.prepareStatement( renameRoles )` → `conn.prepareStatement( RENAME_ROLES )`

In `save()`:
- `conn.prepareStatement( insertProfile )` → `conn.prepareStatement( INSERT_PROFILE )`
- `conn.prepareStatement( findRoles )` → `conn.prepareStatement( FIND_ROLES )`
- `conn.prepareStatement( insertRole )` → `conn.prepareStatement( INSERT_ROLE )`
- `conn.prepareStatement( updateProfile )` → `conn.prepareStatement( UPDATE_PROFILE )`

- [ ] **Step 4: Inline column names in ResultSet calls**

In `getWikiNames()` (around lines 401-403):
- `rs.getString( wikiName )` → `rs.getString( "wiki_name" )`
- `rs.getString( loginName )` → `rs.getString( "login_name" )`

In `findByPreparedStatement()` (around lines 715-730):
- `rs.getString( uid )` → `rs.getString( "uid" )`
- `rs.getTimestamp( created )` → `rs.getTimestamp( "created" )`
- `rs.getString( email )` → `rs.getString( "email" )`
- `rs.getString( fullName )` → `rs.getString( "full_name" )`
- `rs.getTimestamp( modified )` → `rs.getTimestamp( "modified" )`
- `rs.getDate( lockExpiry )` → `rs.getDate( "lock_expiry" )`
- `rs.getString( loginName )` → `rs.getString( "login_name" )`
- `rs.getString( password )` → `rs.getString( "password" )`
- `rs.getString( bio )` → `rs.getString( "bio" )`
- `rs.getString( attributes )` → `rs.getString( "attributes" )`

- [ ] **Step 5: Run JDBCUserDatabaseTest**

Run: `mvn test -pl wikantik-main -Dtest=JDBCUserDatabaseTest -q`
Expected: All tests pass

- [ ] **Step 6: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/auth/user/JDBCUserDatabase.java
git commit -m "refactor: hardcode JDBCUserDatabase SQL, remove column-mapping properties"
```

---

### Task 2: Hardcode JDBCGroupDatabase SQL

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/auth/authorize/JDBCGroupDatabase.java`
- Test: `wikantik-main/src/test/java/com/wikantik/auth/authorize/JDBCGroupDatabaseTest.java` (unchanged — verify passes)

- [ ] **Step 1: Replace constants and instance fields with static SQL constants**

In `JDBCGroupDatabase.java`, delete all 16 `DEFAULT_GROUPDB_*` and `PROP_GROUPDB_*` constants (lines 140-186).

Then delete the column-name instance fields (lines 190-201):
```java
    private String created;
    private String creator;
    private String name;
    private String member;
    private String modified;
    private String modifier;
```

Then delete the SQL instance fields (lines 202-216):
```java
    private String findAll;
    private String findGroup;
    private String findMembers;
    private String insertGroup;
    private String insertGroupMembers;
    private String updateGroup;
    private String deleteGroup;
    private String deleteGroupMembers;
```

Replace all of the above with these static final SQL constants (place them right after the class declaration, before the LOG field):

```java
    private static final String FIND_ALL = "SELECT DISTINCT * FROM groups";
    private static final String FIND_GROUP = "SELECT DISTINCT * FROM groups WHERE name=?";
    private static final String FIND_MEMBERS = "SELECT * FROM group_members WHERE name=?";
    private static final String INSERT_GROUP = "INSERT INTO groups (name,modified,modifier,created,creator) VALUES (?,?,?,?,?)";
    private static final String UPDATE_GROUP = "UPDATE groups SET modified=?,modifier=? WHERE name=?";
    private static final String INSERT_MEMBERS = "INSERT INTO group_members (name,member) VALUES (?,?)";
    private static final String DELETE_GROUP = "DELETE FROM groups WHERE name=?";
    private static final String DELETE_MEMBERS = "DELETE FROM group_members WHERE name=?";
```

- [ ] **Step 2: Simplify initialize()**

Replace the entire `initialize()` method body (lines 398-468) with:

```java
    @Override public void initialize( final Engine engine, final Properties props ) throws NoRequiredPropertyException, WikiSecurityException
    {
        this.engine = engine;

        final String jndiName = props.getProperty( PROP_DATASOURCE, DEFAULT_DATASOURCE );
        try
        {
            final Context initCtx = new InitialContext();
            final Context ctx = (Context) initCtx.lookup( "java:comp/env" );
            ds = (DataSource) ctx.lookup( jndiName );
        }
        catch( final NamingException e )
        {
            LOG.error( "JDBCGroupDatabase initialization error: {}", e.toString() );
            throw new NoRequiredPropertyException( PROP_DATASOURCE, "JDBCGroupDatabase initialization error: " + e);
        }

        // Test connection
        try( final Connection conn = ds.getConnection();
             final PreparedStatement ps = conn.prepareStatement( FIND_ALL ) )
        {
        }
        catch( final SQLException e )
        {
            LOG.error( "DB connectivity error: {}", e.getMessage() );
            throw new WikiSecurityException("DB connectivity error: " + e.getMessage(), e );
        }
        LOG.info( "JDBCGroupDatabase initialized from JNDI DataSource: {}", jndiName );

        // Determine if the datasource supports commits
        try( final Connection conn = ds.getConnection() )
        {
            final DatabaseMetaData dmd = conn.getMetaData();
            if( dmd.supportsTransactions() )
            {
                supportsCommits = true;
                conn.setAutoCommit( false );
                LOG.info( "JDBCGroupDatabase supports transactions. Good; we will use them." );
            }
        }
        catch( final SQLException e )
        {
            LOG.warn( "JDBCGroupDatabase warning: group database doesn't seem to support transactions. Reason: {}", e.getMessage() );
        }
    }
```

- [ ] **Step 3: Update all method references to use new constant names**

Throughout the class:

| Old reference | New reference |
|---|---|
| `findAll` | `FIND_ALL` |
| `findGroup` | `FIND_GROUP` |
| `findMembers` | `FIND_MEMBERS` |
| `insertGroup` | `INSERT_GROUP` |
| `insertGroupMembers` | `INSERT_MEMBERS` |
| `updateGroup` | `UPDATE_GROUP` |
| `deleteGroup` | `DELETE_GROUP` |
| `deleteGroupMembers` | `DELETE_MEMBERS` |

In `delete()`:
- `conn.prepareStatement( deleteGroup )` → `conn.prepareStatement( DELETE_GROUP )`
- `conn.prepareStatement( deleteGroupMembers )` → `conn.prepareStatement( DELETE_MEMBERS )`

In `groups()`:
- `conn.prepareStatement( findAll )` → `conn.prepareStatement( FIND_ALL )`

In `save()`:
- `conn.prepareStatement( insertGroup )` → `conn.prepareStatement( INSERT_GROUP )`
- `conn.prepareStatement( updateGroup )` → `conn.prepareStatement( UPDATE_GROUP )`
- `conn.prepareStatement( deleteGroupMembers )` → `conn.prepareStatement( DELETE_MEMBERS )`
- `conn.prepareStatement( insertGroupMembers )` → `conn.prepareStatement( INSERT_MEMBERS )`

In `findGroup()` (the private method around line 510):
- `conn.prepareStatement( findGroup )` → `conn.prepareStatement( FIND_GROUP )`

In `populateGroup()`:
- `conn.prepareStatement( findMembers )` → `conn.prepareStatement( FIND_MEMBERS )`

- [ ] **Step 4: Inline column names in ResultSet calls**

In `groups()` (around lines 285-296):
- `rs.getString( name )` → `rs.getString( "name" )`
- `rs.getTimestamp( created )` → `rs.getTimestamp( "created" )`
- `rs.getString( creator )` → `rs.getString( "creator" )`
- `rs.getTimestamp( modified )` → `rs.getTimestamp( "modified" )`
- `rs.getString( modifier )` → `rs.getString( "modifier" )`

In the private `findGroup()` method (around lines 521-524):
- `rs.getTimestamp( created )` → `rs.getTimestamp( "created" )`
- `rs.getString( creator )` → `rs.getString( "creator" )`
- `rs.getTimestamp( modified )` → `rs.getTimestamp( "modified" )`
- `rs.getString( modifier )` → `rs.getString( "modifier" )`

In `populateGroup()` (around line 562):
- `rs.getString( member )` → `rs.getString( "member" )`

- [ ] **Step 5: Run JDBCGroupDatabaseTest**

Run: `mvn test -pl wikantik-main -Dtest=JDBCGroupDatabaseTest -q`
Expected: Tests pass (pre-existing H2 infrastructure issue may cause some failures unrelated to this change)

- [ ] **Step 6: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/auth/authorize/JDBCGroupDatabase.java
git commit -m "refactor: hardcode JDBCGroupDatabase SQL, remove column-mapping properties"
```

---

### Task 3: Remove column-mapping properties from config and properties files

**Files:**
- Modify: `wikantik-war/src/main/config/tomcat/wikantik-custom-postgresql.properties.template`
- Modify: `wikantik-main/src/main/resources/ini/wikantik.properties`
- Modify: `wikantik-main/src/test/resources/ini/wikantik.properties`
- Modify: `wikantik-util/src/test/resources/ini/wikantik.properties`

- [ ] **Step 1: Update config template**

In `wikantik-custom-postgresql.properties.template`, delete lines 46-68 (the entire user database and group database column/table mapping blocks):

```properties
# User database table/column mappings (match postgresql.ddl)
wikantik.userdatabase.table = users
wikantik.userdatabase.uid = uid
wikantik.userdatabase.email = email
wikantik.userdatabase.fullName = full_name
wikantik.userdatabase.loginName = login_name
wikantik.userdatabase.password = password
wikantik.userdatabase.wikiName = wiki_name
wikantik.userdatabase.created = created
wikantik.userdatabase.modified = modified
wikantik.userdatabase.lockExpiry = lock_expiry
wikantik.userdatabase.attributes = attributes
wikantik.userdatabase.roleTable = roles
wikantik.userdatabase.role = role

# Group database table/column mappings (match postgresql.ddl)
wikantik.groupdatabase.table = groups
wikantik.groupdatabase.membertable = group_members
wikantik.groupdatabase.name = name
wikantik.groupdatabase.created = created
wikantik.groupdatabase.creator = creator
wikantik.groupdatabase.member = member
wikantik.groupdatabase.modified = modified
wikantik.groupdatabase.modifier = modifier
```

- [ ] **Step 2: Update main default properties**

In `wikantik-main/src/main/resources/ini/wikantik.properties`, delete the `wikantik.userdatabase.*` column-mapping properties (around lines 909-921) and the `wikantik.groupdatabase.*` column-mapping properties (around lines 922-930). These are all lines matching `wikantik.userdatabase.table`, `wikantik.userdatabase.uid`, etc. through `wikantik.groupdatabase.modifier`.

- [ ] **Step 3: Update wikantik-main test properties**

In `wikantik-main/src/test/resources/ini/wikantik.properties`, delete the same blocks of `wikantik.userdatabase.*` and `wikantik.groupdatabase.*` column-mapping properties (around lines 74-96).

- [ ] **Step 4: Update wikantik-util test properties**

In `wikantik-util/src/test/resources/ini/wikantik.properties`, delete the same blocks (around lines 76-98).

- [ ] **Step 5: Commit**

```bash
git add wikantik-war/src/main/config/tomcat/wikantik-custom-postgresql.properties.template \
       wikantik-main/src/main/resources/ini/wikantik.properties \
       wikantik-main/src/test/resources/ini/wikantik.properties \
       wikantik-util/src/test/resources/ini/wikantik.properties
git commit -m "refactor: remove column-mapping properties from all config files"
```

---

### Task 4: Update documentation

**Files:**
- Modify: `docs/DevelopingWithPostgresql.md`
- Modify: `docs/wikantik-pages/DevelopingWithPostgresql.md`

- [ ] **Step 1: Update docs/DevelopingWithPostgresql.md**

Delete the column-mapping property blocks (around lines 565-591). These are the `jspwiki.userdatabase.*` and `jspwiki.groupdatabase.*` property lines in the configuration example. If the surrounding section discusses customizing table/column names, simplify it to note that the schema is fixed.

- [ ] **Step 2: Update docs/wikantik-pages/DevelopingWithPostgresql.md**

Same changes as Step 1 (around lines 571-597).

- [ ] **Step 3: Commit**

```bash
git add docs/DevelopingWithPostgresql.md \
       docs/wikantik-pages/DevelopingWithPostgresql.md
git commit -m "docs: remove column-mapping property examples"
```

---

### Task 5: Full build verification

- [ ] **Step 1: Run full build with unit tests**

Run: `mvn clean install -T 1C -DskipITs`
Expected: BUILD SUCCESS

- [ ] **Step 2: Fix any failures and re-run**

- [ ] **Step 3: Commit fixes if needed**
