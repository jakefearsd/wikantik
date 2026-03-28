# Database-Backed Permissions Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move group storage and default policy grants from static files to PostgreSQL, administrable via the admin UI, with bootstrap admin safety.

**Architecture:** Swap two backing stores behind existing interfaces. `JDBCGroupDatabase` (already exists) gets admin-group guards. New `DatabasePolicy` replaces `LocalPolicy` for policy grant evaluation. `DefaultAuthorizationManager` is modified to use `DatabasePolicy` and check a bootstrap admin override. Two new admin REST endpoints manage groups and policy grants.

**Tech Stack:** Java 21, PostgreSQL 15+, H2/HSQL for tests, JUnit 5, Mockito, existing Wikantik REST/auth framework.

**Spec:** `docs/superpowers/specs/2026-03-28-database-backed-permissions-design.md`

---

## File Structure

| File | Responsibility |
|------|---------------|
| `wikantik-war/src/main/config/db/postgresql-permissions.ddl` | NEW — DDL for `policy_grants` table + seed data |
| `wikantik-main/src/main/java/com/wikantik/auth/authorize/JDBCGroupDatabase.java` | MODIFY — add Admin group guards to `save()` and `delete()` |
| `wikantik-main/src/main/java/com/wikantik/auth/DatabasePolicy.java` | NEW — database-backed policy provider |
| `wikantik-main/src/main/java/com/wikantik/auth/DefaultAuthorizationManager.java` | MODIFY — use DatabasePolicy, add bootstrap admin override |
| `wikantik-rest/src/main/java/com/wikantik/rest/AdminGroupResource.java` | NEW — group admin REST endpoints |
| `wikantik-rest/src/main/java/com/wikantik/rest/AdminPolicyResource.java` | NEW — policy grant admin REST endpoints |
| `wikantik-war/src/main/webapp/WEB-INF/web.xml` | MODIFY — register new admin servlets |
| `wikantik-war/src/main/config/tomcat/wikantik-custom.properties` | MODIFY — add bootstrap property |
| `deploy-local.sh` | MODIFY — run new migration script |
| `wikantik-main/src/test/java/com/wikantik/auth/authorize/JDBCGroupDatabaseTest.java` | MODIFY — add Admin guard tests |
| `wikantik-main/src/test/java/com/wikantik/auth/DatabasePolicyTest.java` | NEW — tests for database policy provider |
| `wikantik-rest/src/test/java/com/wikantik/rest/AdminGroupResourceTest.java` | NEW — tests for group admin endpoints |
| `wikantik-rest/src/test/java/com/wikantik/rest/AdminPolicyResourceTest.java` | NEW — tests for policy admin endpoints |

---

### Task 1: DDL Migration Script

**Files:**
- Create: `wikantik-war/src/main/config/db/postgresql-permissions.ddl`

This task adds the `policy_grants` table and seeds it with default grants matching the current `wikantik.policy` file. The `groups` and `group_members` tables already exist in `postgresql.ddl`.

- [ ] **Step 1: Create the migration DDL file**

```sql
-- postgresql-permissions.ddl
-- Adds policy_grants table for database-backed authorization policy.
-- Run after postgresql.ddl (groups and group_members already exist).
--
-- Usage:
--   sudo -u postgres psql -d wikantik -f postgresql-permissions.ddl

-- Policy grants table: replaces wikantik.policy file
CREATE TABLE IF NOT EXISTS policy_grants (
    id              SERIAL PRIMARY KEY,
    principal_type  VARCHAR(10) NOT NULL,
    principal_name  VARCHAR(255) NOT NULL,
    permission_type VARCHAR(10) NOT NULL,
    target          VARCHAR(255) NOT NULL,
    actions         VARCHAR(255) NOT NULL,
    UNIQUE(principal_type, principal_name, permission_type, target)
);

GRANT SELECT, INSERT, UPDATE, DELETE ON policy_grants TO wikantik;
GRANT USAGE, SELECT ON SEQUENCE policy_grants_id_seq TO wikantik;

-- Seed default policy grants (matches wikantik.policy defaults)

-- All users
INSERT INTO policy_grants (principal_type, principal_name, permission_type, target, actions)
VALUES ('role', 'All', 'page', '*', 'view');
INSERT INTO policy_grants (principal_type, principal_name, permission_type, target, actions)
VALUES ('role', 'All', 'wiki', '*', 'editPreferences,editProfile,login');

-- Anonymous
INSERT INTO policy_grants (principal_type, principal_name, permission_type, target, actions)
VALUES ('role', 'Anonymous', 'page', '*', 'modify');
INSERT INTO policy_grants (principal_type, principal_name, permission_type, target, actions)
VALUES ('role', 'Anonymous', 'wiki', '*', 'createPages');

-- Asserted
INSERT INTO policy_grants (principal_type, principal_name, permission_type, target, actions)
VALUES ('role', 'Asserted', 'page', '*', 'modify');
INSERT INTO policy_grants (principal_type, principal_name, permission_type, target, actions)
VALUES ('role', 'Asserted', 'wiki', '*', 'createPages');
INSERT INTO policy_grants (principal_type, principal_name, permission_type, target, actions)
VALUES ('role', 'Asserted', 'group', '*', 'view');

-- Authenticated
INSERT INTO policy_grants (principal_type, principal_name, permission_type, target, actions)
VALUES ('role', 'Authenticated', 'page', '*', 'modify,rename');
INSERT INTO policy_grants (principal_type, principal_name, permission_type, target, actions)
VALUES ('role', 'Authenticated', 'wiki', '*', 'createPages,createGroups');
INSERT INTO policy_grants (principal_type, principal_name, permission_type, target, actions)
VALUES ('role', 'Authenticated', 'group', '*', 'view');
INSERT INTO policy_grants (principal_type, principal_name, permission_type, target, actions)
VALUES ('role', 'Authenticated', 'group', '<groupmember>', 'edit');

-- Admin (AllPermission)
INSERT INTO policy_grants (principal_type, principal_name, permission_type, target, actions)
VALUES ('role', 'Admin', 'page', '*', '*');
INSERT INTO policy_grants (principal_type, principal_name, permission_type, target, actions)
VALUES ('role', 'Admin', 'wiki', '*', '*');

-- Seed Admin group with admin user (if not already present from postgresql.ddl)
INSERT INTO groups (name, created, modified)
SELECT 'Admin', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM groups WHERE name = 'Admin');

INSERT INTO group_members (name, member)
SELECT 'Admin', 'admin'
WHERE NOT EXISTS (SELECT 1 FROM group_members WHERE name = 'Admin' AND member = 'admin');
```

- [ ] **Step 2: Commit**

```bash
git add wikantik-war/src/main/config/db/postgresql-permissions.ddl
git commit -m "Add DDL migration for policy_grants table with default seed data"
```

---

### Task 2: Admin Group Guards on JDBCGroupDatabase

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/auth/authorize/JDBCGroupDatabase.java`
- Modify: `wikantik-main/src/test/java/com/wikantik/auth/authorize/JDBCGroupDatabaseTest.java`

Add guards that prevent deleting the Admin group or saving it with zero members.

- [ ] **Step 1: Write failing tests for Admin group guards**

Add three tests to `JDBCGroupDatabaseTest.java`:

```java
@Test
public void testDeleteAdminGroupThrows() {
    // The Admin group exists in seed data with 1 member ("Administrator")
    final Group adminGroup = backendGroup( "Admin" );
    Assertions.assertThrows( WikiSecurityException.class, () -> m_db.delete( adminGroup ),
            "Deleting the Admin group must throw WikiSecurityException" );
    // Verify it still exists
    Assertions.assertNotNull( backendGroup( "Admin" ) );
}

@Test
public void testSaveAdminGroupWithZeroMembersThrows() {
    // Create an Admin group object with no members
    final Group emptyAdmin = new Group( "Admin", m_wiki );
    Assertions.assertThrows( WikiSecurityException.class,
            () -> m_db.save( emptyAdmin, new WikiPrincipal( "Tester" ) ),
            "Saving the Admin group with zero members must throw WikiSecurityException" );
    // Verify original Admin group still has its member
    final Group actual = backendGroup( "Admin" );
    Assertions.assertEquals( 1, actual.members().length );
}

@Test
public void testSaveAdminGroupWithMembersSucceeds() throws WikiSecurityException {
    // Save Admin group with a new member — should succeed
    final Group adminGroup = backendGroup( "Admin" );
    adminGroup.add( new WikiPrincipal( "NewAdmin" ) );
    m_db.save( adminGroup, new WikiPrincipal( "Tester" ) );
    final Group updated = backendGroup( "Admin" );
    Assertions.assertEquals( 2, updated.members().length );
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn test -pl wikantik-main -Dtest=JDBCGroupDatabaseTest`
Expected: `testDeleteAdminGroupThrows` and `testSaveAdminGroupWithZeroMembersThrows` FAIL (no guards yet)

- [ ] **Step 3: Implement Admin group guards**

In `JDBCGroupDatabase.java`, add guard to `delete()` method at the top (before the `exists()` check):

```java
@Override public void delete( final Group group ) throws WikiSecurityException
{
    if ( "Admin".equals( group.getName() ) ) {
        throw new WikiSecurityException( "The Admin group cannot be deleted." );
    }
    // ... existing code unchanged
}
```

In `save()` method, add guard after the null check (before `exists()`):

```java
@Override public void save( final Group group, final Principal modifier ) throws WikiSecurityException
{
    if( group == null || modifier == null )
    {
        throw new IllegalArgumentException( "Group or modifier cannot be null." );
    }

    if ( "Admin".equals( group.getName() ) && group.members().length == 0 ) {
        throw new WikiSecurityException(
                "The Admin group must have at least one member. Cannot save with zero members." );
    }

    // ... existing code unchanged
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn test -pl wikantik-main -Dtest=JDBCGroupDatabaseTest`
Expected: All 7 tests PASS

- [ ] **Step 5: Run full GroupManager tests for regression**

Run: `mvn test -pl wikantik-main -Dtest=JDBCGroupDatabaseTest,GroupManagerTest`
Expected: All PASS

- [ ] **Step 6: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/auth/authorize/JDBCGroupDatabase.java \
       wikantik-main/src/test/java/com/wikantik/auth/authorize/JDBCGroupDatabaseTest.java
git commit -m "Guard Admin group: block deletion and empty-member saves"
```

---

### Task 3: DatabasePolicy — Database-Backed Policy Provider

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/auth/DatabasePolicy.java`
- Create: `wikantik-main/src/test/java/com/wikantik/auth/DatabasePolicyTest.java`

This class loads policy grants from the `policy_grants` table and provides an `implies(Principal, Permission)` method that replaces `LocalPolicy.implies()`.

- [ ] **Step 1: Write failing tests for DatabasePolicy**

Create `wikantik-main/src/test/java/com/wikantik/auth/DatabasePolicyTest.java`. Use H2/HSQL in-memory database (same pattern as `JDBCGroupDatabaseTest`). Seed the `policy_grants` table with the default grants.

Key test methods:

```java
@TestInstance( TestInstance.Lifecycle.PER_CLASS )
class DatabasePolicyTest {

    private HsqlDbUtils hu;
    private DatabasePolicy policy;
    private DataSource ds;

    @BeforeAll
    void startDatabase() throws Exception {
        hu = new HsqlDbUtils();
        hu.setUp();
        // Set up JNDI context and bind datasource
        TestJNDIContext.initialize();
        Context initCtx = new InitialContext();
        try { initCtx.bind("java:comp/env", new TestJNDIContext()); }
        catch (NameAlreadyBoundException e) { /* ignore */ }
        Context ctx = (Context) initCtx.lookup("java:comp/env");
        ds = new TestJDBCDataSource(
                new File("target/test-classes/wikantik-custom.properties"), hu.getDriverUrl());
        ctx.bind("jdbc/PolicyDatabase", ds);
    }

    @BeforeEach
    void setUp() throws Exception {
        // Create policy_grants table and seed data
        try (Connection conn = ds.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("DROP TABLE IF EXISTS policy_grants");
            stmt.executeUpdate("CREATE TABLE policy_grants ("
                    + "id INTEGER IDENTITY PRIMARY KEY, "
                    + "principal_type VARCHAR(10) NOT NULL, "
                    + "principal_name VARCHAR(255) NOT NULL, "
                    + "permission_type VARCHAR(10) NOT NULL, "
                    + "target VARCHAR(255) NOT NULL, "
                    + "actions VARCHAR(255) NOT NULL)");
            // Seed default grants (same as DDL)
            seedDefaultGrants(stmt);
        }
        // Initialize policy from database
        policy = new DatabasePolicy(ds, "policy_grants");
    }

    @Test
    void testAuthenticatedRoleHasModifyPermission() {
        Role authenticated = Role.AUTHENTICATED;
        PagePermission perm = new PagePermission("*", "modify");
        assertTrue(policy.implies(authenticated, perm));
    }

    @Test
    void testAuthenticatedRoleHasViewViaImplication() {
        // modify implies view
        Role authenticated = Role.AUTHENTICATED;
        PagePermission perm = new PagePermission("*", "view");
        assertTrue(policy.implies(authenticated, perm));
    }

    @Test
    void testAnonymousRoleLacksDeletePermission() {
        Role anonymous = Role.ANONYMOUS;
        PagePermission perm = new PagePermission("*", "delete");
        assertFalse(policy.implies(anonymous, perm));
    }

    @Test
    void testAdminRoleHasAllPermission() {
        Role admin = Role.ADMIN;  // or new Role("Admin")
        AllPermission perm = new AllPermission("*");
        assertTrue(policy.implies(admin, perm));
    }

    @Test
    void testAllRoleHasViewPermission() {
        Role all = Role.ALL;
        PagePermission perm = new PagePermission("*", "view");
        assertTrue(policy.implies(all, perm));
    }

    @Test
    void testAllRoleLacksEditPermission() {
        Role all = Role.ALL;
        PagePermission perm = new PagePermission("*", "edit");
        assertFalse(policy.implies(all, perm));
    }

    @Test
    void testAuthenticatedHasCreatePages() {
        Role authenticated = Role.AUTHENTICATED;
        WikiPermission perm = new WikiPermission("*", "createPages");
        assertTrue(policy.implies(authenticated, perm));
    }

    @Test
    void testRefreshPicksUpChanges() throws Exception {
        // Initially Anonymous lacks delete
        Role anonymous = Role.ANONYMOUS;
        assertFalse(policy.implies(anonymous, new PagePermission("*", "delete")));

        // Add a grant via SQL
        try (Connection conn = ds.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("INSERT INTO policy_grants "
                    + "(principal_type, principal_name, permission_type, target, actions) "
                    + "VALUES ('role', 'Anonymous', 'page', '*', 'delete')");
        }

        // Before refresh — still cached
        assertFalse(policy.implies(anonymous, new PagePermission("*", "delete")));

        // After refresh — picks up new grant
        policy.refresh();
        assertTrue(policy.implies(anonymous, new PagePermission("*", "delete")));
    }

    @AfterAll
    void stopDatabase() throws Exception {
        hu.tearDown();
    }
}
```

- [ ] **Step 2: Run tests to verify they fail (class not found)**

Run: `mvn test -pl wikantik-main -Dtest=DatabasePolicyTest`
Expected: Compilation failure — `DatabasePolicy` does not exist yet

- [ ] **Step 3: Implement DatabasePolicy**

Create `wikantik-main/src/main/java/com/wikantik/auth/DatabasePolicy.java`:

```java
package com.wikantik.auth;

import com.wikantik.auth.authorize.Role;
import com.wikantik.auth.permissions.AllPermission;
import com.wikantik.auth.permissions.GroupPermission;
import com.wikantik.auth.permissions.PagePermission;
import com.wikantik.auth.permissions.WikiPermission;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.sql.DataSource;
import java.security.Permission;
import java.security.Principal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Database-backed policy provider that replaces the file-based {@code LocalPolicy}.
 * Loads policy grants from the {@code policy_grants} table and evaluates whether
 * a given principal has a given permission.
 */
public class DatabasePolicy {

    private static final Logger LOG = LogManager.getLogger( DatabasePolicy.class );

    private final DataSource dataSource;
    private final String tableName;

    /** Cached grants: principal name → list of permissions. */
    private volatile Map< String, List< Permission > > grants = Collections.emptyMap();

    public DatabasePolicy( final DataSource dataSource, final String tableName ) {
        this.dataSource = dataSource;
        this.tableName = tableName;
        refresh();
    }

    /**
     * Reloads all grants from the database into the in-memory cache.
     */
    public void refresh() {
        final Map< String, List< Permission > > newGrants = new HashMap<>();
        final String sql = "SELECT principal_type, principal_name, permission_type, target, actions FROM " + tableName;

        try ( final Connection conn = dataSource.getConnection();
              final PreparedStatement ps = conn.prepareStatement( sql );
              final ResultSet rs = ps.executeQuery() ) {

            while ( rs.next() ) {
                final String principalName = rs.getString( "principal_name" );
                final String permType = rs.getString( "permission_type" );
                final String target = rs.getString( "target" );
                final String actions = rs.getString( "actions" );

                final Permission perm = buildPermission( permType, target, actions );
                if ( perm != null ) {
                    newGrants.computeIfAbsent( principalName, k -> new ArrayList<>() ).add( perm );
                }
            }
        } catch ( final SQLException e ) {
            LOG.error( "Failed to load policy grants from database: {}", e.getMessage(), e );
        }

        this.grants = Collections.unmodifiableMap( newGrants );
        LOG.info( "Loaded {} policy grants for {} principals",
                  newGrants.values().stream().mapToInt( List::size ).sum(), newGrants.size() );
    }

    /**
     * Returns {@code true} if the given principal has been granted a permission
     * that implies the requested permission.
     */
    public boolean implies( final Principal principal, final Permission requested ) {
        final List< Permission > principalGrants = grants.get( principal.getName() );
        if ( principalGrants != null ) {
            for ( final Permission granted : principalGrants ) {
                if ( granted.implies( requested ) ) {
                    return true;
                }
            }
        }
        return false;
    }

    private Permission buildPermission( final String permType, final String target, final String actions ) {
        if ( "*".equals( actions ) ) {
            return new AllPermission( target );
        }
        return switch ( permType ) {
            case "page" -> new PagePermission( target, actions );
            case "wiki" -> new WikiPermission( target, actions );
            case "group" -> new GroupPermission( target, actions );
            default -> {
                LOG.warn( "Unknown permission type: {}", permType );
                yield null;
            }
        };
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn test -pl wikantik-main -Dtest=DatabasePolicyTest`
Expected: All PASS

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/auth/DatabasePolicy.java \
       wikantik-main/src/test/java/com/wikantik/auth/DatabasePolicyTest.java
git commit -m "Add DatabasePolicy: database-backed policy grant provider"
```

---

### Task 4: Bootstrap Admin Override + DefaultAuthorizationManager Integration

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/auth/DefaultAuthorizationManager.java`
- Modify: `wikantik-main/src/test/java/com/wikantik/auth/DatabasePolicyTest.java` (add bootstrap tests)

Wire `DatabasePolicy` into `DefaultAuthorizationManager`, replacing the `LocalPolicy`. Add bootstrap admin override.

- [ ] **Step 1: Add bootstrap override tests to DatabasePolicyTest**

These test the bootstrap behavior at the `DefaultAuthorizationManager` level. Add to `DatabasePolicyTest` or create a focused test:

```java
@Test
void testBootstrapAdminOverrideGrantsAllPermission() throws Exception {
    // Configure bootstrap override
    Properties props = TestEngine.getTestProperties();
    props.setProperty("wikantik.admin.bootstrap", "admin");
    TestEngine engine = new TestEngine(props);

    // Get an admin session — even if JAAS login fails, the bootstrap
    // override should grant AllPermission based on login principal name matching
    Session session = engine.guestSession();
    // The guest session won't match "admin", so it should NOT get override
    AuthorizationManager authMgr = engine.getManager(AuthorizationManager.class);
    assertFalse(authMgr.checkPermission(session, new AllPermission("*")),
            "Guest session should not get bootstrap override");
}

@Test
void testBootstrapAdminOverrideInactiveWhenNotConfigured() throws Exception {
    Properties props = TestEngine.getTestProperties();
    // Don't set wikantik.admin.bootstrap
    TestEngine engine = new TestEngine(props);
    // Normal behavior — no override
    Session session = engine.guestSession();
    AuthorizationManager authMgr = engine.getManager(AuthorizationManager.class);
    assertFalse(authMgr.checkPermission(session, new AllPermission("*")));
}
```

- [ ] **Step 2: Modify DefaultAuthorizationManager.initialize()**

Replace the `LocalPolicy` initialization block with `DatabasePolicy`:

In `initialize()`, replace lines 237-261 (the `LocalPolicy` setup) with:

```java
// Initialize database-backed security policy
try {
    final String jndiName = properties.getProperty( "wikantik.policy.datasource", "jdbc/WikiantikDS" );
    final String tableName = properties.getProperty( "wikantik.policy.table", "policy_grants" );
    final Context initCtx = new InitialContext();
    final Context ctx = (Context) initCtx.lookup( "java:comp/env" );
    final DataSource policyDs = (DataSource) ctx.lookup( jndiName );
    databasePolicy = new DatabasePolicy( policyDs, tableName );
    LOG.info( "Initialized database-backed security policy from JNDI DataSource: {}", jndiName );
} catch ( final Exception e ) {
    LOG.error( "Could not initialize database security policy: {}", e.getMessage() );
    throw new WikiException( "Could not initialize database security policy: " + e.getMessage(), e );
}

// Bootstrap admin override
bootstrapAdmin = properties.getProperty( PROP_BOOTSTRAP_ADMIN );
if ( bootstrapAdmin != null && !bootstrapAdmin.isBlank() ) {
    LOG.warn( "BOOTSTRAP ADMIN OVERRIDE IS ACTIVE — user '{}' has AllPermission regardless of "
            + "database grants. Remove the wikantik.admin.bootstrap property for production use.",
            bootstrapAdmin );
}
```

Add fields and constant:

```java
public static final String PROP_BOOTSTRAP_ADMIN = "wikantik.admin.bootstrap";
private DatabasePolicy databasePolicy;
private String bootstrapAdmin;
```

- [ ] **Step 3: Modify allowedByLocalPolicy() to use DatabasePolicy**

Replace the `LocalPolicy` delegation with `DatabasePolicy`:

```java
@Override
public boolean allowedByLocalPolicy( final Principal[] principals, final Permission permission ) {
    if ( databasePolicy == null ) {
        LOG.warn( "Database policy not yet initialized - denying access for permission: {}", permission );
        return false;
    }
    for ( final Principal principal : principals ) {
        if ( databasePolicy.implies( principal, permission ) ) {
            return true;
        }
    }
    return false;
}
```

- [ ] **Step 4: Add bootstrap check to checkPermission()**

At the top of `checkPermission()`, before the existing logic, add:

```java
// Bootstrap admin override — if configured, this user always gets AllPermission
if ( bootstrapAdmin != null ) {
    for ( final Principal p : session.getPrincipals() ) {
        if ( bootstrapAdmin.equals( p.getName() ) ) {
            return true;
        }
    }
}
```

- [ ] **Step 5: Remove LocalPolicy imports and field**

Remove the `localPolicy` field, the `LocalPolicy` import, and the `cachedPds` WeakHashMap (no longer needed since `DatabasePolicy` handles caching internally).

- [ ] **Step 6: Run tests**

Run: `mvn test -pl wikantik-main -Dtest=DatabasePolicyTest,AuthorizationManagerTest`
Expected: All PASS. Note: `AuthorizationManagerTest` may need its setup adapted if it currently relies on loading the policy file — the test engine's properties must configure the database policy instead. If tests fail due to JNDI/DataSource issues, the test setup will need a JNDI binding for the policy DataSource (same pattern as `JDBCGroupDatabaseTest`).

- [ ] **Step 7: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/auth/DefaultAuthorizationManager.java \
       wikantik-main/src/test/java/com/wikantik/auth/DatabasePolicyTest.java
git commit -m "Wire DatabasePolicy into DefaultAuthorizationManager with bootstrap admin override"
```

---

### Task 5: AdminGroupResource — Group Admin REST Endpoints

**Files:**
- Create: `wikantik-rest/src/main/java/com/wikantik/rest/AdminGroupResource.java`
- Create: `wikantik-rest/src/test/java/com/wikantik/rest/AdminGroupResourceTest.java`
- Modify: `wikantik-war/src/main/webapp/WEB-INF/web.xml`

Follow the `AdminUserResource` pattern exactly.

- [ ] **Step 1: Write tests for AdminGroupResource**

Create `AdminGroupResourceTest.java` following the `AdminUserResource` test pattern (if one exists) or the `PageResourceTest` pattern. Use `TestEngine`, `HttpMockFactory`, and Mockito.

Key tests:

```java
@Test
void testListGroups() throws Exception {
    // GET /admin/groups — returns list of groups
    // Seed data has groups from engine initialization
}

@Test
void testGetGroup() throws Exception {
    // GET /admin/groups/Admin — returns Admin group with members
}

@Test
void testCreateGroup() throws Exception {
    // PUT /admin/groups/Editors — creates Editors group with members
    // JSON body: {"members": ["alice", "bob"]}
}

@Test
void testDeleteGroup() throws Exception {
    // DELETE /admin/groups/Editors — deletes the group
}

@Test
void testDeleteAdminGroupReturns400() throws Exception {
    // DELETE /admin/groups/Admin — returns 400 (blocked by JDBCGroupDatabase guard)
}
```

- [ ] **Step 2: Implement AdminGroupResource**

Create `AdminGroupResource.java` extending `RestServletBase`. Follow the `AdminUserResource` pattern:

- `doGet()` — list groups (no path param) or get single group (with path param)
- `doPut()` — create/update group from JSON `{"members": ["name1", "name2"]}`
- `doDelete()` — delete group by name

Get the `GroupManager` via `getEngine().getManager(GroupManager.class)`, which provides access to `GroupDatabase`.

- [ ] **Step 3: Register in web.xml**

Add servlet and mapping entries for `AdminGroupResource` at `/admin/groups/*` and `/admin/groups`, following the `AdminUserResource` pattern.

- [ ] **Step 4: Run tests**

Run: `mvn test -pl wikantik-rest -Dtest=AdminGroupResourceTest`
Expected: All PASS

- [ ] **Step 5: Commit**

```bash
git add wikantik-rest/src/main/java/com/wikantik/rest/AdminGroupResource.java \
       wikantik-rest/src/test/java/com/wikantik/rest/AdminGroupResourceTest.java \
       wikantik-war/src/main/webapp/WEB-INF/web.xml
git commit -m "Add AdminGroupResource: REST endpoints for group management"
```

---

### Task 6: AdminPolicyResource — Policy Grant Admin REST Endpoints

**Files:**
- Create: `wikantik-rest/src/main/java/com/wikantik/rest/AdminPolicyResource.java`
- Create: `wikantik-rest/src/test/java/com/wikantik/rest/AdminPolicyResourceTest.java`
- Modify: `wikantik-war/src/main/webapp/WEB-INF/web.xml`

- [ ] **Step 1: Write tests for AdminPolicyResource**

Key tests:

```java
@Test
void testListGrants() throws Exception {
    // GET /admin/policy — returns all policy grants
}

@Test
void testCreateGrant() throws Exception {
    // POST /admin/policy — creates a new grant
    // JSON: {"principalType":"group","principalName":"Editors",
    //        "permissionType":"page","target":"*","actions":"edit"}
}

@Test
void testCreateGrantWithInvalidActions() throws Exception {
    // POST /admin/policy with actions "invalidAction" — returns 400
}

@Test
void testUpdateGrant() throws Exception {
    // PUT /admin/policy/{id} — updates actions
}

@Test
void testDeleteGrant() throws Exception {
    // DELETE /admin/policy/{id} — removes the grant
}
```

- [ ] **Step 2: Implement AdminPolicyResource**

Create `AdminPolicyResource.java` extending `RestServletBase`:

- `doGet()` — list all grants (query `policy_grants` table directly via JDBC)
- `doPost()` — create new grant. Validate `actions` against known action constants. Insert row.
- `doPut()` — update grant by id
- `doDelete()` — delete grant by id

After each mutation, call `DatabasePolicy.refresh()` on the `DatabasePolicy` instance obtained from `DefaultAuthorizationManager`.

Action validation: check each comma-separated action against the valid sets from `PagePermission`, `WikiPermission`, or `GroupPermission` based on `permissionType`. Return 400 for invalid actions.

- [ ] **Step 3: Register in web.xml**

Add servlet and mapping entries for `AdminPolicyResource` at `/admin/policy/*` and `/admin/policy`.

- [ ] **Step 4: Run tests**

Run: `mvn test -pl wikantik-rest -Dtest=AdminPolicyResourceTest`
Expected: All PASS

- [ ] **Step 5: Commit**

```bash
git add wikantik-rest/src/main/java/com/wikantik/rest/AdminPolicyResource.java \
       wikantik-rest/src/test/java/com/wikantik/rest/AdminPolicyResourceTest.java \
       wikantik-war/src/main/webapp/WEB-INF/web.xml
git commit -m "Add AdminPolicyResource: REST endpoints for policy grant management"
```

---

### Task 7: Configuration and Deploy Script Updates

**Files:**
- Modify: `wikantik-war/src/main/config/tomcat/wikantik-custom.properties`
- Modify: `deploy-local.sh`

- [ ] **Step 1: Add bootstrap property to custom properties template**

Add to `wikantik-custom.properties`:

```properties
# Bootstrap admin override — ensures the named user always has AllPermission.
# Set this on first deployment, then remove once you have confirmed admin access works.
# wikantik.admin.bootstrap = admin
```

- [ ] **Step 2: Update deploy-local.sh to run the permissions migration**

Add after the existing DDL execution line:

```bash
# Run permissions migration (idempotent — uses IF NOT EXISTS)
sudo -u postgres psql -d wikantik -f wikantik-war/src/main/config/db/postgresql-permissions.ddl
```

- [ ] **Step 3: Commit**

```bash
git add wikantik-war/src/main/config/tomcat/wikantik-custom.properties deploy-local.sh
git commit -m "Update deploy script and config template for database-backed permissions"
```

---

### Task 8: Full Regression Test

- [ ] **Step 1: Run the complete unit test suite**

```bash
mvn clean test -T 1C -DskipITs
```

Expected: BUILD SUCCESS with zero failures across all modules.

- [ ] **Step 2: Fix any regressions**

If `AuthorizationManagerTest` fails, it's likely because the test setup still expects a `LocalPolicy` file. Update its `@BeforeEach` to set up a JNDI DataSource binding for the policy database (same as `JDBCGroupDatabaseTest`) and seed the `policy_grants` table with the same defaults.

- [ ] **Step 3: Final commit if any fixes were needed**

```bash
git add -A
git commit -m "Fix test regressions for database-backed permissions"
```
