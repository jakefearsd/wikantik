# Hardcode JDBC SQL Statements — Remove Column-Mapping Properties

## Problem

`JDBCUserDatabase` and `JDBCGroupDatabase` build all SQL statements dynamically at
startup by reading 21 properties for table/column names. Every property uses its
default value — the schema has never been customized and never will be. This adds
~44 constants, ~20 instance fields, and ~30 lines of `props.getProperty()` calls
that serve no purpose.

## Design

Replace all property-driven SQL construction with static final SQL string constants.
Remove all `PROP_DB_*`/`DEFAULT_DB_*` and `PROP_GROUPDB_*`/`DEFAULT_GROUPDB_*`
constants, all column-name instance fields, and all SQL-string instance fields.
Inline column name literals directly in SQL constants and `ResultSet.getString()`
calls.

---

### JDBCUserDatabase

**Remove (28 constants):**
- `DEFAULT_DB_ATTRIBUTES`, `DEFAULT_DB_BIO`, `DEFAULT_DB_CREATED`, `DEFAULT_DB_EMAIL`,
  `DEFAULT_DB_FULL_NAME`, `DEFAULT_DB_LOCK_EXPIRY`, `DEFAULT_DB_MODIFIED`, `DEFAULT_DB_ROLE`,
  `DEFAULT_DB_ROLE_TABLE`, `DEFAULT_DB_TABLE`, `DEFAULT_DB_LOGIN_NAME`, `DEFAULT_DB_PASSWORD`,
  `DEFAULT_DB_UID`, `DEFAULT_DB_WIKI_NAME`
- `PROP_DB_ATTRIBUTES`, `PROP_DB_BIO`, `PROP_DB_CREATED`, `PROP_DB_EMAIL`,
  `PROP_DB_FULL_NAME`, `PROP_DB_LOCK_EXPIRY`, `PROP_DB_LOGIN_NAME`, `PROP_DB_MODIFIED`,
  `PROP_DB_PASSWORD`, `PROP_DB_UID`, `PROP_DB_ROLE`, `PROP_DB_ROLE_TABLE`,
  `PROP_DB_TABLE`, `PROP_DB_WIKI_NAME`

**Remove (12 column-name instance fields):**
- `attributes`, `email`, `fullName`, `lockExpiry`, `loginName`, `password`, `uid`,
  `wikiName`, `bio`, `created`, `modified`

**Remove (10 SQL instance fields):**
- `deleteUserByLoginName`, `deleteRoleByLoginName`, `findByEmail`, `findByFullName`,
  `findByLoginName`, `findByUid`, `findByWikiName`, `renameProfile`, `renameRoles`,
  `updateProfile`, `findAll`, `findRoles`, `insertProfile`, `insertRole`

**Add (14 static final SQL constants):**

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
```

**`initialize()` simplification:** Remove all `props.getProperty()` calls for column
names and all SQL string construction. Keep only JNDI lookup, connection test, and
transaction detection.

**ResultSet changes:** All `rs.getString(fieldVariable)` calls become
`rs.getString("column_name")` with inline string literals. For example:
- `rs.getString( uid )` → `rs.getString( "uid" )`
- `rs.getString( fullName )` → `rs.getString( "full_name" )`
- `rs.getTimestamp( created )` → `rs.getTimestamp( "created" )`

---

### JDBCGroupDatabase

**Remove (16 constants):**
- `DEFAULT_GROUPDB_TABLE`, `DEFAULT_GROUPDB_MEMBER_TABLE`, `DEFAULT_GROUPDB_CREATED`,
  `DEFAULT_GROUPDB_CREATOR`, `DEFAULT_GROUPDB_NAME`, `DEFAULT_GROUPDB_MEMBER`,
  `DEFAULT_GROUPDB_MODIFIED`, `DEFAULT_GROUPDB_MODIFIER`
- `PROP_GROUPDB_TABLE`, `PROP_GROUPDB_MEMBER_TABLE`, `PROP_GROUPDB_CREATED`,
  `PROP_GROUPDB_CREATOR`, `PROP_GROUPDB_NAME`, `PROP_GROUPDB_MEMBER`,
  `PROP_GROUPDB_MODIFIED`, `PROP_GROUPDB_MODIFIER`

**Remove (6 column-name instance fields):**
- `created`, `creator`, `name`, `member`, `modified`, `modifier`

**Remove (8 SQL instance fields):**
- `findAll`, `findGroup`, `findMembers`, `insertGroup`, `insertGroupMembers`,
  `updateGroup`, `deleteGroup`, `deleteGroupMembers`

**Add (8 static final SQL constants):**

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

**`initialize()` simplification:** Same as JDBCUserDatabase — remove property reads
and SQL construction, keep JNDI lookup + connection test + transaction detection.

**ResultSet changes:** Same pattern — inline string literals.

---

## Files Changed

### Java Source

| File | Change |
|------|--------|
| `JDBCUserDatabase` | Replace 28 constants + 22 fields with 14 static SQL constants; simplify `initialize()`; inline column names in ResultSet calls |
| `JDBCGroupDatabase` | Replace 16 constants + 14 fields with 8 static SQL constants; simplify `initialize()`; inline column names in ResultSet calls |

### Properties

| File | Change |
|------|--------|
| `wikantik-custom-postgresql.properties.template` | Remove `wikantik.userdatabase.*` and `wikantik.groupdatabase.*` column/table mappings |
| `wikantik-main/src/main/resources/ini/wikantik.properties` | Remove same |
| `wikantik-main/src/test/resources/ini/wikantik.properties` | Remove same |
| `wikantik-util/src/test/resources/ini/wikantik.properties` | Remove same |

### Documentation

| File | Change |
|------|--------|
| `docs/wikantik-pages/RelationalUserDatabase.md` | Remove column-mapping property examples |
| `docs/wikantik-pages/DevelopingWithPostgresql.md` | Remove column-mapping property examples |
| `docs/RelationalUserDatabase.md` | Remove column-mapping property examples |
| `docs/DevelopingWithPostgresql.md` | Remove column-mapping property examples |

## Testing

- Existing `JDBCUserDatabaseTest` and `JDBCGroupDatabaseTest` pass unchanged (they
  never set custom column-name properties — they relied on defaults).
- Full build: `mvn clean install -T 1C -DskipITs`
