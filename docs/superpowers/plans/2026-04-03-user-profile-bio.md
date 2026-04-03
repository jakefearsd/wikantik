# User Profile Bio Field — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a variable-length bio field (max 1000 characters) to user profiles, editable from both the user preferences screen and the admin user form.

**Architecture:** New `bio` column in the `users` table, new getter/setter on `UserProfile`/`DefaultUserProfile`, mapped through `JDBCUserDatabase` following the existing column-property pattern, exposed via REST in both self-service and admin endpoints, with a textarea in the React frontend. Server-side validation rejects bios longer than 1000 characters.

**Tech Stack:** Java 21, HSQL (tests), PostgreSQL (prod), JUnit 5, React/JSX

---

## File Map

| Action | File | Responsibility |
|--------|------|----------------|
| Modify | `wikantik-war/src/main/config/db/hsql.ddl` | Add `bio` column to HSQL test schema |
| Modify | `wikantik-war/src/main/config/db/postgresql.ddl` | Add `bio` column to PostgreSQL schema |
| Modify | `wikantik-main/src/main/java/com/wikantik/auth/user/UserProfile.java` | Add `getBio()`/`setBio()` to interface |
| Modify | `wikantik-main/src/main/java/com/wikantik/auth/user/DefaultUserProfile.java` | Add `bio` field with getter/setter |
| Modify | `wikantik-main/src/main/java/com/wikantik/auth/user/JDBCUserDatabase.java` | Add `bio` column mapping, update SQL statements, read/write bio |
| Modify | `wikantik-main/src/test/java/com/wikantik/auth/user/DefaultUserProfileCITest.java` | Add bio getter/setter tests |
| Modify | `wikantik-main/src/test/java/com/wikantik/auth/user/JDBCUserDatabaseTest.java` | Add bio round-trip test |
| Modify | `wikantik-rest/src/main/java/com/wikantik/rest/AuthResource.java` | Add bio to profile JSON and PUT handler with validation |
| Modify | `wikantik-rest/src/main/java/com/wikantik/rest/AdminUserResource.java` | Add bio to admin profile JSON and PUT/POST handlers with validation |
| Modify | `wikantik-frontend/src/components/UserPreferencesPage.jsx` | Add bio textarea to preferences form |
| Modify | `wikantik-frontend/src/components/admin/UserFormModal.jsx` | Add bio textarea to admin user form |

---

### Task 1: Database Schema — Add `bio` Column

**Files:**
- Modify: `wikantik-war/src/main/config/db/hsql.ddl:21-33`
- Modify: `wikantik-war/src/main/config/db/postgresql.ddl:43-54`

- [ ] **Step 1: Add `bio` column to HSQL DDL**

In `wikantik-war/src/main/config/db/hsql.ddl`, add a `bio` column to the `users` table between `lock_expiry` and `attributes`:

```sql
create table users (
  uid varchar(100),
  email varchar_ignorecase(100),
  full_name varchar(100),
  login_name varchar(100) not null,
  password varchar(100),
  wiki_name varchar(100),
  created timestamp,
  modified timestamp,
  lock_expiry timestamp,
  bio varchar(1000),
  attributes longvarchar,
  constraint users primary key (uid)
);
```

- [ ] **Step 2: Add `bio` column to PostgreSQL DDL**

In `wikantik-war/src/main/config/db/postgresql.ddl`, add a `bio` column to the `users` table between `lock_expiry` and `attributes`:

```sql
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
  bio VARCHAR(1000),
  attributes TEXT
);
```

- [ ] **Step 3: Commit**

```bash
git add wikantik-war/src/main/config/db/hsql.ddl wikantik-war/src/main/config/db/postgresql.ddl
git commit -m "feat(user): add bio column to users table DDL"
```

---

### Task 2: Model — Add `bio` to `UserProfile` Interface and `DefaultUserProfile`

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/auth/user/UserProfile.java:55-56`
- Modify: `wikantik-main/src/main/java/com/wikantik/auth/user/DefaultUserProfile.java:41-50,60-71,73-80`
- Test: `wikantik-main/src/test/java/com/wikantik/auth/user/DefaultUserProfileCITest.java`

- [ ] **Step 1: Write failing tests for bio getter/setter**

Add to `DefaultUserProfileCITest.java`, after the existing test methods:

```java
@Test
void testBioGetterAndSetter() {
    final UserProfile p = db.newProfile();
    assertNull( p.getBio() );
    p.setBio( "I am a wiki enthusiast." );
    assertEquals( "I am a wiki enthusiast.", p.getBio() );
}

@Test
void testBioNullSetter() {
    final UserProfile p = db.newProfile();
    p.setBio( "Something" );
    p.setBio( null );
    assertNull( p.getBio() );
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn test -pl wikantik-main -Dtest=DefaultUserProfileCITest#testBioGetterAndSetter,DefaultUserProfileCITest#testBioNullSetter -T 1C`

Expected: Compilation failure — `getBio()` and `setBio()` don't exist yet.

- [ ] **Step 3: Add `getBio()`/`setBio()` to `UserProfile` interface**

In `UserProfile.java`, add after the `getEmail()` declaration (after line 55):

```java
    /**
     * Returns the user's bio.
     * @return the bio, or {@code null} if not set
     */
    String getBio();
```

And add after the `setEmail()` declaration (after line 139):

```java
    /**
     * Sets the user's bio. Maximum 1000 characters.
     * @param bio the bio text
     */
    void setBio( String bio );
```

- [ ] **Step 4: Add `bio` field to `DefaultUserProfile`**

In `DefaultUserProfile.java`, add the field after line 43 (`private String email;`):

```java
    private String bio;
```

Add the getter after `getEmail()` (after line 103):

```java
    @Override
    public String getBio()
    {
        return bio;
    }
```

Add the setter after `setEmail()` (after line 193):

```java
    @Override
    public void setBio( final String bio )
    {
        this.bio = bio;
    }
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `mvn test -pl wikantik-main -Dtest=DefaultUserProfileCITest -T 1C`

Expected: All tests PASS.

- [ ] **Step 6: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/auth/user/UserProfile.java \
       wikantik-main/src/main/java/com/wikantik/auth/user/DefaultUserProfile.java \
       wikantik-main/src/test/java/com/wikantik/auth/user/DefaultUserProfileCITest.java
git commit -m "feat(user): add bio field to UserProfile interface and DefaultUserProfile"
```

---

### Task 3: JDBC — Map `bio` Column in `JDBCUserDatabase`

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/auth/user/JDBCUserDatabase.java`
- Test: `wikantik-main/src/test/java/com/wikantik/auth/user/JDBCUserDatabaseTest.java`

- [ ] **Step 1: Write failing test for bio round-trip**

Add to `JDBCUserDatabaseTest.java`:

```java
@Test
public void testSaveAndRetrieveBio() throws WikiSecurityException {
    final String loginName = "BioUser" + System.currentTimeMillis();
    UserProfile profile = m_db.newProfile();
    profile.setEmail( "bio@mailinator.com" );
    profile.setLoginName( loginName );
    profile.setFullname( "Bio User" );
    profile.setPassword( Users.ALICE_PASS );
    profile.setBio( "I love editing wikis." );
    m_db.save( profile );

    // Retrieve and verify bio was persisted
    profile = m_db.findByLoginName( loginName );
    assertEquals( "I love editing wikis.", profile.getBio() );

    // Update bio
    profile.setBio( "Updated bio text." );
    m_db.save( profile );

    profile = m_db.findByLoginName( loginName );
    assertEquals( "Updated bio text.", profile.getBio() );

    // Clear bio
    profile.setBio( null );
    m_db.save( profile );

    profile = m_db.findByLoginName( loginName );
    assertNull( profile.getBio() );

    // Clean up
    m_db.deleteByLoginName( loginName );
}
```

Also add this import at the top if not present:
```java
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl wikantik-main -Dtest=JDBCUserDatabaseTest#testSaveAndRetrieveBio -T 1C`

Expected: FAIL — bio column doesn't exist in SQL statements or schema.

- [ ] **Step 3: Add bio constants and field to `JDBCUserDatabase`**

Add new constants after line 220 (`DEFAULT_DB_WIKI_NAME`), keeping alphabetical order with existing constants:

```java
    public static final String DEFAULT_DB_BIO = "bio";
```

Add new property constant after line 248 (`PROP_DB_WIKI_NAME`):

```java
    public static final String PROP_DB_BIO = "wikantik.userdatabase.bio";
```

Add new instance field after line 294 (`private String wikiName;`):

```java
    private String bio;
```

- [ ] **Step 4: Update `initialize()` to read bio column config and include bio in SQL**

In `initialize()`, after line 432 (`wikiName = props.getProperty(...)`), add:

```java
            bio = props.getProperty( PROP_DB_BIO, DEFAULT_DB_BIO );
```

Update the `insertProfile` SQL (currently lines 445-455) to include `bio` as parameter 9, shifting `created` to parameter 10:

```java
            insertProfile = "INSERT INTO " + userTable + " ("
                              + uid + ","
                              + email + ","
                              + fullName + ","
                              + password + ","
                              + wikiName + ","
                              + modified + ","
                              + loginName + ","
                              + attributes + ","
                              + bio + ","
                              + created
                              + ") VALUES (?,?,?,?,?,?,?,?,?,?)";
```

Update the `updateProfile` SQL (currently lines 458-468) to include `bio` as parameter 9, shifting `lockExpiry` to 10 and the WHERE `loginName` to 11:

```java
            updateProfile = "UPDATE " + userTable + " SET "
                              + uid + "=?,"
                              + email + "=?,"
                              + fullName + "=?,"
                              + password + "=?,"
                              + wikiName + "=?,"
                              + modified + "=?,"
                              + loginName + "=?,"
                              + attributes + "=?,"
                              + bio + "=?,"
                              + lockExpiry + "=? "
                              + "WHERE " + loginName + "=?";
```

- [ ] **Step 5: Update `setProfileParameters()` to include bio**

The method currently sets parameters 1-8. Add bio as parameter 9 (after the attributes at parameter 8). Update the method (lines 663-677):

```java
    private void setProfileParameters( final PreparedStatement ps, final UserProfile profile,
                                        final String password, final Timestamp ts ) throws WikiSecurityException, SQLException {
        ps.setString( 1, profile.getUid() );
        ps.setString( 2, profile.getEmail() );
        ps.setString( 3, profile.getFullname() );
        ps.setString( 4, password );
        ps.setString( 5, profile.getWikiName() );
        ps.setTimestamp( 6, ts );
        ps.setString( 7, profile.getLoginName() );
        try {
            ps.setString( 8, Serializer.serializeToBase64( profile.getAttributes() ) );
        } catch ( final IOException e ) {
            throw new WikiSecurityException( "Could not save user profile attribute. Reason: " + e.getMessage(), e );
        }
        ps.setString( 9, profile.getBio() );
    }
```

- [ ] **Step 6: Update `save()` parameter indices**

In the `save()` method, the INSERT path (line 612) sets `created` as parameter 9. It is now parameter 10:

```java
                ps1.setTimestamp( 10, ts );
```

The UPDATE path (lines 635-636) sets `lockExpiry` as parameter 9 and WHERE `loginName` as parameter 10. They are now 10 and 11:

```java
                ps4.setDate( 10, lockExpiry );
                ps4.setString( 11, profile.getLoginName() );
```

- [ ] **Step 7: Update `findByPreparedStatement()` to read bio**

In the `findByPreparedStatement()` method, after line 719 (`profile.setPassword( rs.getString( password ) );`), add:

```java
                    profile.setBio( rs.getString( bio ) );
```

- [ ] **Step 8: Run test to verify it passes**

Run: `mvn test -pl wikantik-main -Dtest=JDBCUserDatabaseTest -T 1C`

Expected: ALL tests PASS, including `testSaveAndRetrieveBio`.

- [ ] **Step 9: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/auth/user/JDBCUserDatabase.java \
       wikantik-main/src/test/java/com/wikantik/auth/user/JDBCUserDatabaseTest.java
git commit -m "feat(user): map bio column in JDBCUserDatabase with round-trip test"
```

---

### Task 4: REST — Expose `bio` in Auth and Admin Endpoints

**Files:**
- Modify: `wikantik-rest/src/main/java/com/wikantik/rest/AuthResource.java:188-263,394-403`
- Modify: `wikantik-rest/src/main/java/com/wikantik/rest/AdminUserResource.java:164-208,210-242,299-315`

- [ ] **Step 1: Add bio to `AuthResource.profileToMap()`**

In `AuthResource.java`, update the `profileToMap()` method (line 394-403) to include `bio`:

```java
    private Map< String, Object > profileToMap( final UserProfile profile ) {
        final Map< String, Object > map = new LinkedHashMap<>();
        map.put( "loginName", profile.getLoginName() );
        map.put( "fullName", profile.getFullname() );
        map.put( "email", profile.getEmail() );
        map.put( "bio", profile.getBio() );
        map.put( "wikiName", profile.getWikiName() );
        map.put( "created", formatDate( profile.getCreated() ) );
        map.put( "lastModified", formatDate( profile.getLastModified() ) );
        return map;
    }
```

- [ ] **Step 2: Add bio handling to `AuthResource.handleUpdateProfile()`**

In `handleUpdateProfile()`, after the email update block (after line 219), add bio update with validation:

```java
            // Update bio if provided
            final String bio = getJsonString( body, "bio" );
            if ( bio != null ) {
                if ( bio.length() > 1000 ) {
                    sendError( response, HttpServletResponse.SC_BAD_REQUEST,
                            "Bio must be 1000 characters or fewer" );
                    return;
                }
                profile.setBio( bio );
            }
```

- [ ] **Step 3: Add bio to `AdminUserResource.profileToMap()`**

In `AdminUserResource.java`, update the `profileToMap()` method (lines 299-315) to include `bio` after `email`:

```java
    private Map< String, Object > profileToMap( final UserProfile profile ) {
        final Map< String, Object > map = new LinkedHashMap<>();
        map.put( "loginName", profile.getLoginName() );
        map.put( "fullName", profile.getFullname() );
        map.put( "email", profile.getEmail() );
        map.put( "bio", profile.getBio() );
        map.put( "wikiName", profile.getWikiName() );
        map.put( "created", formatDate( profile.getCreated() ) );
        map.put( "lastModified", formatDate( profile.getLastModified() ) );

        final Date lockExpiry = profile.getLockExpiry();
        final boolean locked = lockExpiry != null && lockExpiry.after( new Date() );
        map.put( "locked", locked );
        if ( locked ) {
            map.put( "lockExpiry", formatDate( lockExpiry ) );
        }
        return map;
    }
```

- [ ] **Step 4: Add bio handling to `AdminUserResource.handleCreateUser()`**

In `handleCreateUser()`, after line 172 (`final String email = ...`), add:

```java
        final String bio = getJsonString( body, "bio" );
```

After line 194 (`if ( email != null ) profile.setEmail( email );`), add:

```java
            if ( bio != null ) {
                if ( bio.length() > 1000 ) {
                    sendError( response, HttpServletResponse.SC_BAD_REQUEST,
                            "Bio must be 1000 characters or fewer" );
                    return;
                }
                profile.setBio( bio );
            }
```

- [ ] **Step 5: Add bio handling to `AdminUserResource.handleUpdateUser()`**

In `handleUpdateUser()`, after line 221 (`final String password = ...`), add:

```java
            final String bio = getJsonString( body, "bio" );
```

After line 224 (`if ( email != null ) profile.setEmail( email );`), add:

```java
            if ( bio != null ) {
                if ( bio.length() > 1000 ) {
                    sendError( response, HttpServletResponse.SC_BAD_REQUEST,
                            "Bio must be 1000 characters or fewer" );
                    return;
                }
                profile.setBio( bio );
            }
```

- [ ] **Step 6: Run full build to verify compilation**

Run: `mvn test -pl wikantik-rest -T 1C`

Expected: PASS (no runtime tests for REST yet, but compilation must succeed).

- [ ] **Step 7: Commit**

```bash
git add wikantik-rest/src/main/java/com/wikantik/rest/AuthResource.java \
       wikantik-rest/src/main/java/com/wikantik/rest/AdminUserResource.java
git commit -m "feat(user): expose bio field in auth and admin REST endpoints"
```

---

### Task 5: Frontend — Add Bio Textarea to User Preferences

**Files:**
- Modify: `wikantik-frontend/src/components/UserPreferencesPage.jsx`

- [ ] **Step 1: Add bio state variable**

In `UserPreferencesPage.jsx`, after line 12 (`const [email, setEmail] = useState('');`), add:

```jsx
  const [bio, setBio] = useState('');
```

- [ ] **Step 2: Load bio from profile**

In the `loadProfile` function, after line 35 (`setEmail(data.email || '');`), add:

```jsx
      setBio(data.bio || '');
```

- [ ] **Step 3: Include bio in form submission**

In `handleSubmit`, update line 55 from:

```jsx
      const data = { fullName, email };
```

to:

```jsx
      const data = { fullName, email, bio };
```

- [ ] **Step 4: Add bio textarea to the Profile Information fieldset**

After the Email field `</div>` (after line 209), add:

```jsx
          <div style={fieldStyle}>
            <label style={labelStyle}>Bio</label>
            <textarea
              value={bio}
              onChange={e => setBio(e.target.value)}
              maxLength={1000}
              rows={4}
              style={{
                ...inputStyle,
                resize: 'vertical',
                minHeight: '80px',
              }}
              placeholder="Tell others about yourself..."
            />
            <div style={{
              fontSize: '0.75rem',
              color: bio.length > 950 ? 'var(--color-danger, #ef4444)' : 'var(--text-muted)',
              textAlign: 'right',
              marginTop: 'var(--space-xs)',
            }}>
              {bio.length} / 1000
            </div>
          </div>
```

- [ ] **Step 5: Verify manually (optional) or run frontend build**

Run: `cd wikantik-frontend && npm run build`

Expected: Build succeeds without errors.

- [ ] **Step 6: Commit**

```bash
git add wikantik-frontend/src/components/UserPreferencesPage.jsx
git commit -m "feat(user): add bio textarea to user preferences page"
```

---

### Task 6: Frontend — Add Bio Textarea to Admin User Form

**Files:**
- Modify: `wikantik-frontend/src/components/admin/UserFormModal.jsx`

- [ ] **Step 1: Add bio to form state**

In `UserFormModal.jsx`, update line 5:

```jsx
  const [form, setForm] = useState({ loginName: '', fullName: '', email: '', bio: '', password: '' });
```

Update line 11 (the edit branch in useEffect):

```jsx
      setForm({ loginName: user.loginName, fullName: user.fullName || '', email: user.email || '', bio: user.bio || '', password: '' });
```

Update line 13 (the reset branch):

```jsx
      setForm({ loginName: '', fullName: '', email: '', bio: '', password: '' });
```

- [ ] **Step 2: Add bio textarea to form**

After the Email form field (after line 68), add:

```jsx
          <div className="form-field">
            <label>Bio</label>
            <textarea
              value={form.bio}
              onChange={set('bio')}
              maxLength={1000}
              rows={3}
              placeholder="User bio..."
            />
            <small style={{ color: 'var(--color-muted, #888)', display: 'block', marginTop: '0.25rem' }}>
              {form.bio.length} / 1000
            </small>
          </div>
```

- [ ] **Step 3: Verify frontend build**

Run: `cd wikantik-frontend && npm run build`

Expected: Build succeeds without errors.

- [ ] **Step 4: Commit**

```bash
git add wikantik-frontend/src/components/admin/UserFormModal.jsx
git commit -m "feat(user): add bio textarea to admin user form modal"
```

---

### Task 7: Full Build Verification

- [ ] **Step 1: Run full unit test suite**

Run: `mvn clean test -T 1C`

Expected: ALL tests pass. Pay attention to `JDBCUserDatabaseTest` and `DefaultUserProfileCITest`.

- [ ] **Step 2: Run full build including WAR packaging**

Run: `mvn clean install -Dmaven.test.skip -T 1C`

Expected: Build succeeds, WAR is generated.

- [ ] **Step 3: Apply database migration to local PostgreSQL**

The local database needs the new column. Run:

```bash
PGPASSWORD="<db-password>" psql -h localhost -U jspwiki -d jspwiki -c "ALTER TABLE users ADD COLUMN IF NOT EXISTS bio VARCHAR(1000);"
```

(This step is for manual testing only — the DDL file captures the schema for fresh installs.)
