# Consolidate JNDI DataSources into Single `wikantik.datasource`

## Problem

The application defines 3 identical JNDI connection pools (`jdbc/UserDatabase`,
`jdbc/GroupDatabase`, `jdbc/KnowledgeDatabase`) all pointing to the same PostgreSQL
database with the same credentials and pool sizes. This wastes 60 connections when
20-30 would suffice, and requires users to maintain 3 identical `<Resource>` blocks
plus 4 separate property lines in their configuration.

This is legacy from when the wiki had a file-based storage option. The application
now always runs on PostgreSQL.

## Design

Replace the 3 pools and 4 property names with a single property and a single pool.

### New Property

```properties
wikantik.datasource = jdbc/WikiDatabase
```

### Removed Properties

- `wikantik.userdatabase.datasource`
- `wikantik.groupdatabase.datasource`
- `wikantik.policy.datasource`
- `wikantik.knowledge.datasource`

### Shared Constant

`AbstractJDBCDatabase` (the common base for `JDBCUserDatabase` and `JDBCGroupDatabase`)
gets the canonical property name and default:

```java
public static final String PROP_DATASOURCE = "wikantik.datasource";
public static final String DEFAULT_DATASOURCE = "jdbc/WikiDatabase";
```

All subsystems reference these constants (or the string literal in modules that
cannot import `AbstractJDBCDatabase`).

### Behavioral Changes

- **Policy grants:** Previously opt-in via `wikantik.policy.datasource`.
  Now always active — `DefaultAuthorizationManager` reads `wikantik.datasource`
  unconditionally.
- **Knowledge graph:** Previously opt-in via `wikantik.knowledge.datasource`.
  Now always initialized when the datasource is configured. `WikiEngine.initKnowledgeGraph()`
  reads `wikantik.datasource`.

---

## Files Changed

### Java Source

| File | Change |
|------|--------|
| `AbstractJDBCDatabase` | Add `PROP_DATASOURCE` and `DEFAULT_DATASOURCE` constants |
| `JDBCUserDatabase` | Remove `PROP_DB_DATASOURCE`, `DEFAULT_DB_JNDI_NAME`. Read `PROP_DATASOURCE` with `DEFAULT_DATASOURCE` fallback. Update javadoc. |
| `JDBCGroupDatabase` | Remove `PROP_GROUPDB_DATASOURCE`, `DEFAULT_GROUPDB_DATASOURCE`. Read `PROP_DATASOURCE` with `DEFAULT_DATASOURCE` fallback. Update javadoc. |
| `DefaultAuthorizationManager` | Remove `PROP_POLICY_DATASOURCE`. Read `PROP_DATASOURCE` unconditionally. |
| `WikiEngine.initKnowledgeGraph()` | Read `PROP_DATASOURCE` instead of `wikantik.knowledge.datasource`. |
| `ObservabilityLifecycleExtension` | Read `wikantik.datasource` instead of `wikantik.userdatabase.datasource`. Update default. |

### Config Templates

| File | Change |
|------|--------|
| `Wikantik-context.xml.template` | Replace 3 `<Resource>` blocks with single `jdbc/WikiDatabase` (`maxTotal="30"`) |
| `wikantik-custom-postgresql.properties.template` | Replace 4 datasource properties with single `wikantik.datasource = jdbc/WikiDatabase` |

### Default Properties

| File | Change |
|------|--------|
| `wikantik-main/src/main/resources/ini/wikantik.properties` | Replace `wikantik.userdatabase.datasource` and `wikantik.groupdatabase.datasource` with `wikantik.datasource = jdbc/WikiDatabase` |

### Test Resources

| File | Change |
|------|--------|
| `wikantik-main/src/test/resources/ini/wikantik.properties` | Same as default properties |
| `wikantik-util/src/test/resources/ini/wikantik.properties` | Same as default properties |
| `wikantik-main/src/test/resources/WEB-INF/web.xml` | Update `res-ref-name` entries |
| `wikantik-main/src/test/java/com/wikantik/TestJNDIContext.java` | Bind `jdbc/WikiDatabase` instead of separate names |
| `wikantik-observability/.../ObservabilityLifecycleExtensionTest.java` | Use `wikantik.datasource` property name |
| `wikantik-it-tests/pom.xml` | Single Cargo JNDI resource `jdbc/WikiDatabase` |

### Documentation

| File | Change |
|------|--------|
| `CLAUDE.md` | Update property references |
| `docs/RelationalUserDatabase.md` | Update examples |
| `docs/DevelopingWithPostgresql.md` | Update examples |
| `docs/migration-1.0-to-1.1.md` | Update property references |
| `docs/wikantik-pages/RelationalUserDatabase.md` | Update examples |
| `docs/wikantik-pages/DevelopingWithPostgresql.md` | Update examples |
| `docs/wikantik-pages/WikantikOnDocker.md` | Update DataSource reference |
| `docs/wikantik-pages/DatabaseBackedPermissions.md` | Update property reference |

## Testing

- Unit tests: `JDBCUserDatabaseTest`, `JDBCGroupDatabaseTest`, `DefaultAuthorizationManagerTest`,
  `ObservabilityLifecycleExtensionTest` all pass with the new property name.
- Full build: `mvn clean install -T 1C -DskipITs` passes.

## Production Migration Summary

When deploying this update, the following config changes are needed:

**`ROOT.xml` (Tomcat context):**
- Remove `jdbc/UserDatabase`, `jdbc/GroupDatabase`, `jdbc/KnowledgeDatabase` resources
- Add single `jdbc/WikiDatabase` resource with `maxTotal="30"`

**`wikantik-custom.properties`:**
- Remove: `wikantik.userdatabase.datasource`, `wikantik.groupdatabase.datasource`,
  `wikantik.policy.datasource`, `wikantik.knowledge.datasource`
- Add: `wikantik.datasource = jdbc/WikiDatabase`
