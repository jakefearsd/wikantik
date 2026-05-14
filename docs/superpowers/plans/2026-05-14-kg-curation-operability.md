# KG Curation Operability Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Four KG curation operability fixes — admin-bypass on read paths, refuse ghost merges, node_type vocabulary gate at the service boundary, and helpful errors on nested `curate_{nodes,edges}.upsert` shapes — per `docs/superpowers/specs/2026-05-14-kg-curation-operability-design.md`.

**Architecture:** Add `boolean adminBypass` overloads to `KgInclusionFilter` (SQL accessor), `KgNodeRepository`, and `KnowledgeGraphService`. Wire bypass=true on `AdminKnowledgeResource`, on the existing admin MCP tools that read (`InspectProposalsTool`, `ListProposalsTool`), and on two new admin-only registrations of `QueryNodesTool`/`SearchKnowledgeTool` on `/wikantik-admin-mcp`. Add a service-tier regex on `upsertNode`, a service-tier existence check on `mergeNodes`, and shape-error detection in the curate tools. Ship a one-shot operator cleanup script for pre-existing node_type pollution. No DB migration.

**Tech Stack:** Java 21, JUnit 5, Mockito, Postgres (pgvector), MCP SDK (`io.modelcontextprotocol`), Maven multi-module reactor, Cargo-launched Tomcat ITs.

**Spec:** `docs/superpowers/specs/2026-05-14-kg-curation-operability-design.md`

---

## File Structure

**Created files:**
- `wikantik-main/src/test/java/com/wikantik/kgpolicy/KgInclusionFilterBypassTest.java`
- `wikantik-main/src/test/java/com/wikantik/knowledge/KgNodeRepositoryBypassTest.java`
- `wikantik-knowledge/src/test/java/com/wikantik/knowledge/curation/ProposalConflictFlagsBypassTest.java` (extends existing test or new file)
- `wikantik-it-tests/wikantik-selenide-tests/src/main/java/com/wikantik/its/mcp/KgCurationVisibilityIT.java`
- `bin/kg-cleanup-node-types.sh`

**Modified files:**
- `wikantik-main/src/main/java/com/wikantik/kgpolicy/KgInclusionFilter.java` — add bypass accessors
- `wikantik-main/src/main/java/com/wikantik/knowledge/KgNodeRepository.java` — add bypass overloads
- `wikantik-api/src/main/java/com/wikantik/api/knowledge/KnowledgeGraphService.java` — add bypass overloads
- `wikantik-main/src/main/java/com/wikantik/knowledge/DefaultKnowledgeGraphService.java` — implement overloads, add regex + merge existence checks
- `wikantik-main/src/test/java/com/wikantik/knowledge/DefaultKnowledgeGraphServiceTest.java` — new test cases
- `wikantik-knowledge/src/main/java/com/wikantik/api/knowledge/ProposalConflictFlags.java` — accept bypass flag
- `wikantik-rest/src/main/java/com/wikantik/rest/AdminKnowledgeResource.java` — flip bypass=true on all read handlers
- `wikantik-admin-mcp/src/main/java/com/wikantik/mcp/tools/ListProposalsTool.java` — pass bypass=true to the helper
- `wikantik-admin-mcp/src/main/java/com/wikantik/mcp/tools/InspectProposalsTool.java` — pass bypass=true to getNodeByName
- `wikantik-admin-mcp/src/main/java/com/wikantik/mcp/tools/ProposeKnowledgeTool.java` — symmetric regex validation
- `wikantik-admin-mcp/src/main/java/com/wikantik/mcp/tools/CurateNodesTool.java` — nested-shape error in doUpsert
- `wikantik-admin-mcp/src/main/java/com/wikantik/mcp/tools/CurateEdgesTool.java` — nested-shape error in doUpsert
- `wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/QueryNodesTool.java` — third ctor with `adminBypass`
- `wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/SearchKnowledgeTool.java` — third ctor with `adminBypass`
- `wikantik-admin-mcp/src/main/java/com/wikantik/mcp/McpToolRegistry.java` — register admin-bypass copies of `QueryNodesTool` + `SearchKnowledgeTool`
- `wikantik-admin-mcp/src/main/resources/wikantik-mcp-instructions.txt` — add entries for the two new admin reads
- `wikantik-it-tests/wikantik-selenide-tests/src/main/java/com/wikantik/its/mcp/McpProtocolIT.java` — EXPECTED_TOOLS 22 → 24
- `wikantik-it-tests/wikantik-selenide-tests/src/main/java/com/wikantik/its/mcp/KgCurationIT.java` — three new ITs (merge ghost, upsert polluted type, optional visibility cross-check)
- `wikantik-it-tests/wikantik-selenide-tests/src/main/java/com/wikantik/its/mcp/WithMcpTestSetup.java` — seed accessors for the visibility fixtures
- `wikantik-it-tests/src/main/resources/sql/it-test-seed.sql` — seed an excluded-page node + an allowed-page node for visibility tests
- `wikantik-admin-mcp/src/test/java/com/wikantik/mcp/tools/ProposeKnowledgeToolTest.java` — symmetric regex test cases
- `wikantik-admin-mcp/src/test/java/com/wikantik/mcp/tools/CurateNodesToolTest.java` + `CurateEdgesToolTest.java` — nested-shape test cases
- `CLAUDE.md` — tool count 22 → 24
- `docs/wikantik-pages/KgInclusionPolicy.md` — document admin-bypass behaviour

---

## Task 1: `KgInclusionFilter` admin-bypass accessors

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/kgpolicy/KgInclusionFilter.java`
- Create: `wikantik-main/src/test/java/com/wikantik/kgpolicy/KgInclusionFilterBypassTest.java`

- [ ] **Step 1: Write the failing test**

Create `KgInclusionFilterBypassTest.java`:

```java
/*
    Licensed to the Apache Software Foundation (ASF) under one
    or more contributor license agreements. ... (standard ASF header)
 */
package com.wikantik.kgpolicy;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class KgInclusionFilterBypassTest {

    @Test
    void nodeFilterReturnsExistingFragmentsWhenBypassIsFalse() {
        assertEquals( KgInclusionFilter.NODE_FILTER_JOIN, KgInclusionFilter.nodeFilterJoin( false ) );
        assertEquals( KgInclusionFilter.NODE_FILTER_WHERE, KgInclusionFilter.nodeFilterWhere( false ) );
    }

    @Test
    void nodeFilterReturnsEmptyAndTrueWhenBypassIsTrue() {
        assertEquals( "", KgInclusionFilter.nodeFilterJoin( true ) );
        assertEquals( " TRUE ", KgInclusionFilter.nodeFilterWhere( true ) );
    }

    @Test
    void edgeFilterRespectsBypass() {
        assertEquals( KgInclusionFilter.EDGE_FILTER_JOIN, KgInclusionFilter.edgeFilterJoin( false ) );
        assertEquals( KgInclusionFilter.EDGE_FILTER_WHERE, KgInclusionFilter.edgeFilterWhere( false ) );
        assertEquals( "", KgInclusionFilter.edgeFilterJoin( true ) );
        assertEquals( " TRUE ", KgInclusionFilter.edgeFilterWhere( true ) );
    }

    @Test
    void mentionFilterRespectsBypass() {
        assertEquals( KgInclusionFilter.MENTION_FILTER_JOIN, KgInclusionFilter.mentionFilterJoin( false ) );
        assertEquals( KgInclusionFilter.MENTION_FILTER_WHERE, KgInclusionFilter.mentionFilterWhere( false ) );
        assertEquals( "", KgInclusionFilter.mentionFilterJoin( true ) );
        assertEquals( " TRUE ", KgInclusionFilter.mentionFilterWhere( true ) );
    }
}
```

- [ ] **Step 2: Run, expect compile fail**

Run: `mvn -pl wikantik-main -Dtest=KgInclusionFilterBypassTest test-compile`
Expected: compile error (accessor methods don't exist).

- [ ] **Step 3: Add accessors to `KgInclusionFilter`**

Add to `wikantik-main/src/main/java/com/wikantik/kgpolicy/KgInclusionFilter.java` at the end of the class (preserve all existing static constants):

```java
/** Empty fragment replacement for {@link #NODE_FILTER_JOIN} when admin bypass is on. */
public static final String NODE_FILTER_JOIN_BYPASS = "";
/** "TRUE" fragment replacement for {@link #NODE_FILTER_WHERE} when admin bypass is on. */
public static final String NODE_FILTER_WHERE_BYPASS = " TRUE ";
/** Empty fragment replacement for {@link #EDGE_FILTER_JOIN} when admin bypass is on. */
public static final String EDGE_FILTER_JOIN_BYPASS = "";
/** "TRUE" fragment replacement for {@link #EDGE_FILTER_WHERE} when admin bypass is on. */
public static final String EDGE_FILTER_WHERE_BYPASS = " TRUE ";
/** Empty fragment replacement for {@link #MENTION_FILTER_JOIN} when admin bypass is on. */
public static final String MENTION_FILTER_JOIN_BYPASS = "";
/** "TRUE" fragment replacement for {@link #MENTION_FILTER_WHERE} when admin bypass is on. */
public static final String MENTION_FILTER_WHERE_BYPASS = " TRUE ";

/**
 * Returns either the production NODE_FILTER_JOIN fragment or the empty
 * bypass fragment based on {@code adminBypass}. Use this accessor at any
 * call site that might be invoked from an admin curation context.
 */
public static String nodeFilterJoin( final boolean adminBypass ) {
    return adminBypass ? NODE_FILTER_JOIN_BYPASS : NODE_FILTER_JOIN;
}

public static String nodeFilterWhere( final boolean adminBypass ) {
    return adminBypass ? NODE_FILTER_WHERE_BYPASS : NODE_FILTER_WHERE;
}

public static String edgeFilterJoin( final boolean adminBypass ) {
    return adminBypass ? EDGE_FILTER_JOIN_BYPASS : EDGE_FILTER_JOIN;
}

public static String edgeFilterWhere( final boolean adminBypass ) {
    return adminBypass ? EDGE_FILTER_WHERE_BYPASS : EDGE_FILTER_WHERE;
}

public static String mentionFilterJoin( final boolean adminBypass ) {
    return adminBypass ? MENTION_FILTER_JOIN_BYPASS : MENTION_FILTER_JOIN;
}

public static String mentionFilterWhere( final boolean adminBypass ) {
    return adminBypass ? MENTION_FILTER_WHERE_BYPASS : MENTION_FILTER_WHERE;
}
```

- [ ] **Step 4: Run, expect pass**

Run: `mvn -pl wikantik-main -Dtest=KgInclusionFilterBypassTest test 2>&1 | tail -10`
Expected: 4/4 tests pass.

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/kgpolicy/KgInclusionFilter.java \
        wikantik-main/src/test/java/com/wikantik/kgpolicy/KgInclusionFilterBypassTest.java
git commit -m "feat(kg): KgInclusionFilter admin-bypass accessor + bypass fragments"
```

---

## Task 2: `KgNodeRepository` + `KnowledgeGraphService` bypass overloads

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/knowledge/KgNodeRepository.java`
- Modify: `wikantik-api/src/main/java/com/wikantik/api/knowledge/KnowledgeGraphService.java`
- Modify: `wikantik-main/src/main/java/com/wikantik/knowledge/DefaultKnowledgeGraphService.java`
- Create: `wikantik-main/src/test/java/com/wikantik/knowledge/KgNodeRepositoryBypassTest.java`

- [ ] **Step 1: Write a unit test that verifies the new overloads use the correct SQL fragments**

The test uses an H2 in-memory DB (or a mock JDBC layer) and inspects the SQL string the repository builds. Use whatever pattern existing `KgNodeRepositoryTest` uses for DB-side verification; if it stands up a real Postgres via Testcontainers, mirror that.

If no DB-side test infrastructure exists, write a **string-assertion test** by extracting the SQL-build logic into a small package-private helper and asserting on its output:

Create `KgNodeRepositoryBypassTest.java`:

```java
/*
    ... standard ASF header
 */
package com.wikantik.knowledge;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

public class KgNodeRepositoryBypassTest {

    @Test
    void queryNodesSqlOmitsExcludedJoinWhenBypassIsTrue() {
        final String sql = KgNodeRepository.buildQueryNodesSql( null, null, true );
        assertFalse( sql.contains( "kg_excluded_pages" ),
                "Admin bypass must omit the kg_excluded_pages join: " + sql );
        assertTrue( sql.contains( " TRUE " ), "Bypass should splice in TRUE: " + sql );
    }

    @Test
    void queryNodesSqlIncludesExcludedJoinByDefault() {
        final String sql = KgNodeRepository.buildQueryNodesSql( null, null, false );
        assertTrue( sql.contains( "kg_excluded_pages" ),
                "Non-bypass query must apply the exclusion filter: " + sql );
        assertTrue( sql.contains( "kgxn.page_name IS NULL" ),
                "Non-bypass query must keep the NULL predicate: " + sql );
    }

    @Test
    void searchNodesSqlRespectsBypass() {
        final String byp = KgNodeRepository.buildSearchNodesSql( null, true );
        final String std = KgNodeRepository.buildSearchNodesSql( null, false );
        assertFalse( byp.contains( "kg_excluded_pages" ) );
        assertTrue( std.contains( "kg_excluded_pages" ) );
    }
}
```

- [ ] **Step 2: Run, expect compile fail**

Run: `mvn -pl wikantik-main -Dtest=KgNodeRepositoryBypassTest test-compile`
Expected: compile error (`buildQueryNodesSql` / `buildSearchNodesSql` not exposed).

- [ ] **Step 3: Extract `buildQueryNodesSql` / `buildSearchNodesSql` as package-private static helpers and add the bypass overloads to `KgNodeRepository`**

Open `wikantik-main/src/main/java/com/wikantik/knowledge/KgNodeRepository.java`. At the top of the class (after the constants), add the SQL-build helpers extracted from the existing `queryNodes`/`searchNodes` methods:

```java
/** Package-private SQL builder for {@link #queryNodes}. Allows test introspection. */
static String buildQueryNodesSql( final Map< String, Object > filters,
                                   final Set< Provenance > provenanceFilter,
                                   final boolean adminBypass ) {
    final StringBuilder sql = new StringBuilder( "SELECT n.* FROM kg_nodes n" )
            .append( KgInclusionFilter.nodeFilterJoin( adminBypass ) )
            .append( "WHERE" ).append( KgInclusionFilter.nodeFilterWhere( adminBypass ) );
    if ( filters != null ) {
        if ( filters.containsKey( "node_type" ) ) sql.append( " AND n.node_type = ?" );
        if ( filters.containsKey( "source_page" ) ) sql.append( " AND n.source_page = ?" );
        if ( filters.containsKey( "name" ) ) sql.append( " AND LOWER( n.name ) LIKE ?" );
        if ( filters.containsKey( "status" ) ) sql.append( " AND n.properties->>'status' = ?" );
    }
    if ( provenanceFilter != null && !provenanceFilter.isEmpty() ) {
        sql.append( " AND n.provenance IN (" );
        final java.util.StringJoiner sj = new java.util.StringJoiner( ", " );
        for ( int i = 0; i < provenanceFilter.size(); i++ ) sj.add( "?" );
        sql.append( sj ).append( ')' );
    }
    sql.append( " ORDER BY n.name LIMIT ? OFFSET ?" );
    return sql.toString();
}

/** Package-private SQL builder for {@link #searchNodes}. Allows test introspection. */
static String buildSearchNodesSql( final Set< Provenance > provenanceFilter,
                                    final boolean adminBypass ) {
    final StringBuilder sql = new StringBuilder( "SELECT n.* FROM kg_nodes n" )
            .append( KgInclusionFilter.nodeFilterJoin( adminBypass ) )
            .append( "WHERE" ).append( KgInclusionFilter.nodeFilterWhere( adminBypass ) )
            .append( " AND ( LOWER( n.name ) LIKE ? OR LOWER( n.properties::text ) LIKE ? )" );
    if ( provenanceFilter != null && !provenanceFilter.isEmpty() ) {
        sql.append( " AND n.provenance IN (" );
        final java.util.StringJoiner sj = new java.util.StringJoiner( ", " );
        for ( int i = 0; i < provenanceFilter.size(); i++ ) sj.add( "?" );
        sql.append( sj ).append( ')' );
    }
    sql.append( " ORDER BY n.name LIMIT ?" );
    return sql.toString();
}
```

Now refactor the existing `queryNodes(filters, prov, limit, offset)` and `searchNodes(query, prov, limit)` bodies to call these helpers. Add the new bypass overloads:

```java
public List< KgNode > queryNodes( final Map< String, Object > filters,
                                   final Set< Provenance > provenanceFilter,
                                   final int limit, final int offset ) {
    return queryNodes( filters, provenanceFilter, limit, offset, false );
}

public List< KgNode > queryNodes( final Map< String, Object > filters,
                                   final Set< Provenance > provenanceFilter,
                                   final int limit, final int offset,
                                   final boolean adminBypass ) {
    final String sql = buildQueryNodesSql( filters, provenanceFilter, adminBypass );
    final List< Object > params = new ArrayList<>();
    if ( filters != null ) {
        if ( filters.containsKey( "node_type" ) ) params.add( filters.get( "node_type" ) );
        if ( filters.containsKey( "source_page" ) ) params.add( filters.get( "source_page" ) );
        if ( filters.containsKey( "name" ) ) params.add( "%" + filters.get( "name" ).toString().toLowerCase( Locale.ROOT ) + "%" );
        if ( filters.containsKey( "status" ) ) params.add( filters.get( "status" ) );
    }
    if ( provenanceFilter != null && !provenanceFilter.isEmpty() ) {
        for ( final Provenance p : provenanceFilter ) params.add( p.value() );
    }
    params.add( limit );
    params.add( offset );

    final List< KgNode > results = new ArrayList<>();
    try ( Connection conn = dataSource.getConnection();
          PreparedStatement ps = conn.prepareStatement( sql ) ) {
        for ( int i = 0; i < params.size(); i++ ) ps.setObject( i + 1, params.get( i ) );
        try ( ResultSet rs = ps.executeQuery() ) {
            while ( rs.next() ) results.add( mapNode( rs ) );
        }
    } catch ( final SQLException e ) {
        LOG.warn( "Failed to query nodes: {}", e.getMessage(), e );
        throw new RuntimeException( e );
    }
    return results;
}
```

Apply the same pattern to `searchNodes`. Also add bypass overloads for `getNode(UUID, boolean)` and `getNodeByName(String, boolean)`. For those, the existing methods are small — just write a parallel SQL build using `KgInclusionFilter.nodeFilterJoin/Where(adminBypass)`. Keep the existing single-arg methods delegating to `(id, false)` and `(name, false)`.

- [ ] **Step 4: Add the bypass overloads to `KnowledgeGraphService` interface**

In `wikantik-api/src/main/java/com/wikantik/api/knowledge/KnowledgeGraphService.java`, add (as abstract methods alongside the existing ones):

```java
List< KgNode > queryNodes( Map< String, Object > filters, Set< Provenance > provenanceFilter,
                           int limit, int offset, boolean adminBypass );

List< KgNode > searchKnowledge( String query, Set< Provenance > provenanceFilter,
                                int limit, boolean adminBypass );

KgNode getNode( UUID id, boolean adminBypass );

KgNode getNodeByName( String name, boolean adminBypass );
```

(Yes, this widens the interface. Each new method has one new param. The existing methods keep working.)

- [ ] **Step 5: Implement the new methods on `DefaultKnowledgeGraphService`**

In `wikantik-main/src/main/java/com/wikantik/knowledge/DefaultKnowledgeGraphService.java`, add:

```java
@Override
public List< KgNode > queryNodes( final Map< String, Object > filters,
                                   final Set< Provenance > provenanceFilter,
                                   final int limit, final int offset,
                                   final boolean adminBypass ) {
    return nodes.queryNodes( filters, provenanceFilter, limit, offset, adminBypass );
}

@Override
public List< KgNode > searchKnowledge( final String query, final Set< Provenance > provenanceFilter,
                                        final int limit, final boolean adminBypass ) {
    return nodes.searchNodes( query, provenanceFilter, limit, adminBypass );
}

@Override
public KgNode getNode( final UUID id, final boolean adminBypass ) {
    return nodes.getNode( id, adminBypass );
}

@Override
public KgNode getNodeByName( final String name, final boolean adminBypass ) {
    return nodes.getNodeByName( name, adminBypass );
}
```

- [ ] **Step 6: Run, expect pass**

Run: `mvn -pl wikantik-main -Dtest=KgNodeRepositoryBypassTest test 2>&1 | tail -10`
Expected: 3/3 tests pass.

Run: `mvn -pl wikantik-api,wikantik-main -am test 2>&1 | tail -10`
Expected: all existing tests still pass; the interface widening doesn't break any existing impl callers because new methods are added (not modified).

- [ ] **Step 7: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/knowledge/KgNodeRepository.java \
        wikantik-main/src/test/java/com/wikantik/knowledge/KgNodeRepositoryBypassTest.java \
        wikantik-api/src/main/java/com/wikantik/api/knowledge/KnowledgeGraphService.java \
        wikantik-main/src/main/java/com/wikantik/knowledge/DefaultKnowledgeGraphService.java
git commit -m "feat(kg): admin-bypass overloads on KgNodeRepository + KnowledgeGraphService"
```

---

## Task 3: Wire admin-bypass on REST + curator-facing MCP read tools

**Files:**
- Modify: `wikantik-rest/src/main/java/com/wikantik/rest/AdminKnowledgeResource.java`
- Modify: `wikantik-knowledge/src/main/java/com/wikantik/api/knowledge/ProposalConflictFlags.java` — accept bypass
- Modify: `wikantik-admin-mcp/src/main/java/com/wikantik/mcp/tools/ListProposalsTool.java`
- Modify: `wikantik-admin-mcp/src/main/java/com/wikantik/mcp/tools/InspectProposalsTool.java`
- Modify: `wikantik-admin-mcp/src/test/java/com/wikantik/mcp/tools/ListProposalsToolTest.java` — verify bypass on
- Modify: `wikantik-admin-mcp/src/test/java/com/wikantik/mcp/tools/InspectProposalsToolTest.java`
- Modify: `wikantik-knowledge/src/test/java/com/wikantik/api/knowledge/ProposalConflictFlagsTest.java`

- [ ] **Step 1: Add bypass parameter to `ProposalConflictFlags`**

The current signature is `forProposal(KnowledgeGraphService, KgProposal)`. Add an overload:

```java
public static Map< String, Object > forProposal( final KnowledgeGraphService svc, final KgProposal p,
                                                  final boolean adminBypass ) {
    // existing logic, but every getNodeByName(name) call becomes getNodeByName(name, adminBypass)
    final Map< String, Object > flags = new LinkedHashMap<>();
    if ( p == null || p.proposedData() == null ) return flags;
    try {
        if ( "new-node".equals( p.proposalType() ) ) {
            final Object name = p.proposedData().get( "name" );
            if ( name instanceof String s && !s.isBlank() ) {
                final KgNode existing = svc.getNodeByName( s, adminBypass );
                flags.put( "node_exists", existing != null );
                if ( existing != null ) flags.put( "existing_node_id", existing.id().toString() );
            }
        } else if ( "new-edge".equals( p.proposalType() ) ) {
            // isRejected does not consult kg_excluded_pages — no bypass needed
            // (existing body unchanged)
        }
    } catch ( final Exception e ) {
        LOG.warn( "Failed to compute conflict flags for proposal {}: {}", p.id(), e.getMessage() );
    }
    return flags;
}

/** Backwards-compatible overload — bypass off, matches current public callers. */
public static Map< String, Object > forProposal( final KnowledgeGraphService svc, final KgProposal p ) {
    return forProposal( svc, p, false );
}
```

Update `ProposalConflictFlagsTest.java` to add one new test:

```java
@Test
void forProposalThreadsBypassFlagThroughGetNodeByName() {
    final KnowledgeGraphService svc = Mockito.mock( KnowledgeGraphService.class );
    final KgProposal p = Mockito.mock( KgProposal.class );
    when( p.proposalType() ).thenReturn( "new-node" );
    when( p.proposedData() ).thenReturn( Map.of( "name", "Raft" ) );

    ProposalConflictFlags.forProposal( svc, p, true );

    Mockito.verify( svc ).getNodeByName( "Raft", true );
    Mockito.verify( svc, Mockito.never() ).getNodeByName( "Raft", false );
}
```

- [ ] **Step 2: Update `ListProposalsTool` to pass bypass=true**

In `wikantik-admin-mcp/src/main/java/com/wikantik/mcp/tools/ListProposalsTool.java`, find the line that calls `ProposalConflictFlags.forProposal(service, p)` (was added during the KG curation MCP work). Change to:

```java
map.putAll( com.wikantik.api.knowledge.ProposalConflictFlags.forProposal( service, p, true ) );
```

Update `ListProposalsToolTest` — find the existing `executeAddsNodeExistsFlagForNewNodeProposal` test. It currently stubs `svc.getNodeByName("Raft")`. Update the stub to match the bypass-flag call:

```java
when( svc.getNodeByName( "Raft", true ) ).thenReturn( Mockito.mock( KgNode.class ) );
```

Add a new test:

```java
@Test
void executeUsesAdminBypassForConflictFlagLookups() {
    final KnowledgeGraphService svc = Mockito.mock( KnowledgeGraphService.class );
    final KgProposal p = Mockito.mock( KgProposal.class );
    when( p.id() ).thenReturn( UUID.randomUUID() );
    when( p.proposalType() ).thenReturn( "new-node" );
    when( p.proposedData() ).thenReturn( Map.of( "name", "Raft" ) );
    when( p.status() ).thenReturn( "pending" );
    when( svc.listProposals( "pending", null, 50, 0 ) ).thenReturn( List.of( p ) );

    new ListProposalsTool( svc ).execute( Map.of( "status", "pending" ) );

    Mockito.verify( svc ).getNodeByName( "Raft", true );
}
```

- [ ] **Step 3: Update `InspectProposalsTool` to pass bypass=true**

In `InspectProposalsTool.linkedEntity(KgProposal)`, the call is currently `service.getNodeByName(s)`. Change to `service.getNodeByName(s, true)`. Add a similar Mockito verify test in `InspectProposalsToolTest`.

- [ ] **Step 4: Update `AdminKnowledgeResource` REST read handlers to bypass**

Open `wikantik-rest/src/main/java/com/wikantik/rest/AdminKnowledgeResource.java`. Find `handleGetNodes`, `handleGetSimilarNodes`, and any other read handler that calls `service.queryNodes / searchKnowledge / getNode / getNodeByName`. Replace each call with the bypass overload (`, true)` appended).

Also: where `proposalToMap` builds the conflict-flag block via `ProposalConflictFlags.forProposal(service, p)`, change to `(service, p, true)`.

For each REST handler change, add (or update) the matching test assertion in `AdminKnowledgeResourceTest` to verify bypass-true is being passed (use Mockito argument capture on a mock `KnowledgeGraphService`).

- [ ] **Step 5: Run tests**

```
mvn -pl wikantik-knowledge -Dtest=ProposalConflictFlagsTest test
mvn -pl wikantik-admin-mcp -Dtest=ListProposalsToolTest,InspectProposalsToolTest test
mvn -pl wikantik-rest test
```

Expected: all pass.

- [ ] **Step 6: Commit**

```bash
git add wikantik-knowledge/src/main/java/com/wikantik/api/knowledge/ProposalConflictFlags.java \
        wikantik-knowledge/src/test/java/com/wikantik/api/knowledge/ProposalConflictFlagsTest.java \
        wikantik-admin-mcp/src/main/java/com/wikantik/mcp/tools/ListProposalsTool.java \
        wikantik-admin-mcp/src/main/java/com/wikantik/mcp/tools/InspectProposalsTool.java \
        wikantik-admin-mcp/src/test/java/com/wikantik/mcp/tools/ListProposalsToolTest.java \
        wikantik-admin-mcp/src/test/java/com/wikantik/mcp/tools/InspectProposalsToolTest.java \
        wikantik-rest/src/main/java/com/wikantik/rest/AdminKnowledgeResource.java
git commit -m "feat(kg): wire admin-bypass on REST + curator MCP read paths"
```

---

## Task 4: Register admin-bypass `QueryNodesTool` + `SearchKnowledgeTool` on `/wikantik-admin-mcp`

**Files:**
- Modify: `wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/QueryNodesTool.java`
- Modify: `wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/SearchKnowledgeTool.java`
- Modify: `wikantik-admin-mcp/src/main/java/com/wikantik/mcp/McpToolRegistry.java`
- Modify: `wikantik-admin-mcp/src/test/java/com/wikantik/mcp/McpToolRegistryTest.java`
- Modify: `wikantik-it-tests/wikantik-selenide-tests/src/main/java/com/wikantik/its/mcp/McpProtocolIT.java`
- Modify: `wikantik-admin-mcp/src/main/resources/wikantik-mcp-instructions.txt`

- [ ] **Step 1: Add admin-bypass ctor to `QueryNodesTool`**

In `wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/QueryNodesTool.java`, the existing constructors are:

```java
public QueryNodesTool( final KnowledgeGraphService service ) { this( service, null ); }
public QueryNodesTool( final KnowledgeGraphService service, final MentionIndex mentionIndex ) {
    this.service = service;
    this.mentionIndex = mentionIndex;
}
```

Add a field and a third constructor:

```java
private final boolean adminBypass;

public QueryNodesTool( final KnowledgeGraphService service ) { this( service, null, false ); }
public QueryNodesTool( final KnowledgeGraphService service, final MentionIndex mentionIndex ) {
    this( service, mentionIndex, false );
}
public QueryNodesTool( final KnowledgeGraphService service, final MentionIndex mentionIndex,
                       final boolean adminBypass ) {
    this.service = service;
    this.mentionIndex = mentionIndex;
    this.adminBypass = adminBypass;
}
```

In `execute(...)`, find the call to `service.queryNodes(filters, prov, limit, offset)`. Replace with `service.queryNodes(filters, prov, limit, offset, adminBypass)`.

Add a unit test in the existing `QueryNodesToolTest.java`:

```java
@Test
void executePassesAdminBypassFlagWhenSet() {
    final KnowledgeGraphService svc = Mockito.mock( KnowledgeGraphService.class );
    when( svc.queryNodes( any(), any(), anyInt(), anyInt(), eq( true ) ) ).thenReturn( List.of() );

    new QueryNodesTool( svc, null, true ).execute( Map.of() );

    Mockito.verify( svc ).queryNodes( any(), any(), anyInt(), anyInt(), eq( true ) );
}

@Test
void executeDefaultsToBypassFalse() {
    final KnowledgeGraphService svc = Mockito.mock( KnowledgeGraphService.class );
    when( svc.queryNodes( any(), any(), anyInt(), anyInt(), eq( false ) ) ).thenReturn( List.of() );

    new QueryNodesTool( svc ).execute( Map.of() );

    Mockito.verify( svc ).queryNodes( any(), any(), anyInt(), anyInt(), eq( false ) );
}
```

- [ ] **Step 2: Apply the same pattern to `SearchKnowledgeTool`**

Add a field, a third constructor accepting `adminBypass`, and update the `service.searchKnowledge(...)` call to pass it. Add the same two tests.

- [ ] **Step 3: Register the admin-bypass copies in `McpToolRegistry`**

In `wikantik-admin-mcp/src/main/java/com/wikantik/mcp/McpToolRegistry.java`, inside the existing `if ( kgService != null )` block, add (after the existing curation tool registrations):

```java
// Admin-only mirrors of the read tools from /knowledge-mcp, with admin
// bypass enabled so curators can immediately see entities they just wrote
// even when the source page is on kg_excluded_pages.
readOnlyList.add( new com.wikantik.knowledge.mcp.QueryNodesTool( kgService, null, /*adminBypass=*/ true ) );
readOnlyList.add( new com.wikantik.knowledge.mcp.SearchKnowledgeTool( kgService, /*adminBypass=*/ true ) );
```

If `SearchKnowledgeTool` has a different ctor shape (e.g., requires other deps), match what `KnowledgeMcpInitializer` passes when registering it on `/knowledge-mcp` — read that file to mirror the production wiring.

- [ ] **Step 4: Update `McpToolRegistryTest`**

The existing `registerCurationToolsWhenKgServiceAvailable` test asserts a list of tool names. Extend its assertions to also include `"query_nodes"` and `"search_knowledge"` in the read-only list. If a count assertion exists, bump from N to N+2.

- [ ] **Step 5: Update `McpProtocolIT.EXPECTED_TOOLS` from 22 → 24**

In `wikantik-it-tests/wikantik-selenide-tests/src/main/java/com/wikantik/its/mcp/McpProtocolIT.java`, find the `EXPECTED_TOOLS` `Set.of(...)` literal. Add:

```java
"query_nodes", "search_knowledge",
```

…to whichever logical group reads best (probably the knowledge graph curation group). Total entries must now be 24.

- [ ] **Step 6: Document the two new tools in `wikantik-mcp-instructions.txt`**

Append entries to `wikantik-admin-mcp/src/main/resources/wikantik-mcp-instructions.txt`:

```
query_nodes — Admin-context filter+list of KG nodes. Bypasses the
inclusion filter so freshly-created entities (whose source pages have not
yet been admitted by the cluster inclusion policy) are visible. Mirrors
/knowledge-mcp/query_nodes but with admin-bypass on. Use after a
curate_nodes upsert to verify the write.

search_knowledge — Admin-context fuzzy search across KG node names and
property text. Admin-bypass on. Use after a curate_nodes or
review_proposals approval to find the new entity by name.
```

- [ ] **Step 7: Build verification**

```
mvn -pl wikantik-admin-mcp,wikantik-knowledge -am test 2>&1 | tail -10
mvn -pl wikantik-it-tests/wikantik-selenide-tests install -DskipTests -q
mvn -pl wikantik-it-tests/wikantik-it-test-custom -Pintegration-tests -Dit.test='McpProtocolIT,McpInstructionsDriftIT' verify 2>&1 | grep -E "Tests run|BUILD" | tail -3
```

Expected: BUILD SUCCESS. `McpProtocolIT.listToolsReturnsAllTools` confirms 24 tools, `McpInstructionsDriftIT` confirms the instructions text mentions the two new tools.

- [ ] **Step 8: Commit**

```bash
git add wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/QueryNodesTool.java \
        wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/SearchKnowledgeTool.java \
        wikantik-knowledge/src/test/java/com/wikantik/knowledge/mcp/QueryNodesToolTest.java \
        wikantik-knowledge/src/test/java/com/wikantik/knowledge/mcp/SearchKnowledgeToolTest.java \
        wikantik-admin-mcp/src/main/java/com/wikantik/mcp/McpToolRegistry.java \
        wikantik-admin-mcp/src/test/java/com/wikantik/mcp/McpToolRegistryTest.java \
        wikantik-admin-mcp/src/main/resources/wikantik-mcp-instructions.txt \
        wikantik-it-tests/wikantik-selenide-tests/src/main/java/com/wikantik/its/mcp/McpProtocolIT.java
git commit -m "feat(mcp): register admin-bypass query_nodes + search_knowledge on /wikantik-admin-mcp"
```

---

## Task 5: Refuse ghost merges

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/knowledge/DefaultKnowledgeGraphService.java`
- Modify: `wikantik-main/src/test/java/com/wikantik/knowledge/DefaultKnowledgeGraphServiceTest.java`
- Modify: `wikantik-it-tests/wikantik-selenide-tests/src/main/java/com/wikantik/its/mcp/KgCurationIT.java`

- [ ] **Step 1: Write failing unit tests**

Append to `DefaultKnowledgeGraphServiceTest.java`:

```java
@Test
void mergeNodes_throwsWhenSourceMissing() {
    final UUID source = UUID.randomUUID();
    final UUID target = UUID.randomUUID();
    when( nodes.getNode( source ) ).thenReturn( null );
    when( nodes.getNode( target ) ).thenReturn( Mockito.mock( KgNode.class ) );

    final IllegalStateException e = assertThrows( IllegalStateException.class,
            () -> service.mergeNodes( source, target ) );
    assertTrue( e.getMessage().contains( "merge source not found" ), e.getMessage() );
}

@Test
void mergeNodes_throwsWhenTargetMissing() {
    final UUID source = UUID.randomUUID();
    final UUID target = UUID.randomUUID();
    when( nodes.getNode( source ) ).thenReturn( Mockito.mock( KgNode.class ) );
    when( nodes.getNode( target ) ).thenReturn( null );

    final IllegalStateException e = assertThrows( IllegalStateException.class,
            () -> service.mergeNodes( source, target ) );
    assertTrue( e.getMessage().contains( "merge target not found" ), e.getMessage() );
}

@Test
void mergeNodes_succeedsWhenBothPresent() {
    final UUID source = UUID.randomUUID();
    final UUID target = UUID.randomUUID();
    when( nodes.getNode( source ) ).thenReturn( Mockito.mock( KgNode.class ) );
    when( nodes.getNode( target ) ).thenReturn( Mockito.mock( KgNode.class ) );
    when( edges.getEdgesForNode( eq( source ), anyString() ) ).thenReturn( List.of() );

    assertDoesNotThrow( () -> service.mergeNodes( source, target ) );
    Mockito.verify( nodes ).deleteNode( source );
}
```

- [ ] **Step 2: Run, expect 2 failures (source-missing and target-missing tests fail because the existing impl doesn't check)**

Run: `mvn -pl wikantik-main -Dtest=DefaultKnowledgeGraphServiceTest test 2>&1 | tail -15`

- [ ] **Step 3: Add existence checks to `mergeNodes`**

In `DefaultKnowledgeGraphService.java`, find `mergeNodes(UUID, UUID)`. Prepend:

```java
@Override
public void mergeNodes( final UUID sourceId, final UUID targetId ) {
    if ( nodes.getNode( sourceId ) == null ) {
        throw new IllegalStateException( "merge source not found: " + sourceId );
    }
    if ( nodes.getNode( targetId ) == null ) {
        throw new IllegalStateException( "merge target not found: " + targetId );
    }
    // existing body (edge migration + deleteNode + snapshotBuilder.invalidateCache) unchanged
}
```

- [ ] **Step 4: Run, expect pass**

Run: `mvn -pl wikantik-main -Dtest=DefaultKnowledgeGraphServiceTest test 2>&1 | tail -10`
Expected: 3 new tests pass + existing tests still pass.

- [ ] **Step 5: Add an IT**

In `wikantik-it-tests/wikantik-selenide-tests/src/main/java/com/wikantik/its/mcp/KgCurationIT.java`, append:

```java
@Test
public void curateNodesMergeWithGhostSourceIsPerOpError() {
    final String ghostUuid = "00000000-0000-0000-0000-000000000000";
    final String realTarget = WithMcpTestSetup.seededNodeId();

    final McpSchema.CallToolResult r = mcp.callTool( "curate_nodes",
            Map.of( "operations", List.of( Map.of(
                    "action", "merge", "tag", "ghost",
                    "source_id", ghostUuid,
                    "target_id", realTarget ) ) ) );

    Assertions.assertFalse( r.isError() );
    final String body = ( ( McpSchema.TextContent ) r.content().get( 0 ) ).text();
    Assertions.assertTrue( body.contains( "\"failed\"" ), body );
    Assertions.assertTrue( body.contains( "merge source not found" ), body );
    Assertions.assertTrue( body.contains( ghostUuid ), body );
}
```

- [ ] **Step 6: Run targeted IT**

```
mvn -pl wikantik-it-tests/wikantik-selenide-tests install -DskipTests -q
mvn -pl wikantik-it-tests/wikantik-it-test-custom -Pintegration-tests -Dit.test='KgCurationIT#curateNodesMergeWithGhostSourceIsPerOpError' verify 2>&1 | grep -E "Tests run|BUILD" | tail -3
```

Expected: BUILD SUCCESS.

- [ ] **Step 7: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/knowledge/DefaultKnowledgeGraphService.java \
        wikantik-main/src/test/java/com/wikantik/knowledge/DefaultKnowledgeGraphServiceTest.java \
        wikantik-it-tests/wikantik-selenide-tests/src/main/java/com/wikantik/its/mcp/KgCurationIT.java
git commit -m "fix(kg): refuse mergeNodes when source or target UUID is missing"
```

---

## Task 6: Node-type vocabulary regex at `upsertNode` + `propose_knowledge`

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/knowledge/DefaultKnowledgeGraphService.java`
- Modify: `wikantik-main/src/test/java/com/wikantik/knowledge/DefaultKnowledgeGraphServiceTest.java`
- Modify: `wikantik-admin-mcp/src/main/java/com/wikantik/mcp/tools/ProposeKnowledgeTool.java`
- Modify: `wikantik-admin-mcp/src/test/java/com/wikantik/mcp/tools/ProposeKnowledgeToolTest.java`
- Modify: `wikantik-it-tests/wikantik-selenide-tests/src/main/java/com/wikantik/its/mcp/KgCurationIT.java`

- [ ] **Step 1: Write failing unit tests**

Append to `DefaultKnowledgeGraphServiceTest.java`:

```java
@Test
void upsertNode_rejectsTrailingCommaTypo() {
    final IllegalArgumentException e = assertThrows( IllegalArgumentException.class,
            () -> service.upsertNode( "Raft", "concept,", "PaxosAndRaft",
                    Provenance.HUMAN_AUTHORED, Map.of() ) );
    assertTrue( e.getMessage().contains( "invalid node_type" ), e.getMessage() );
}

@Test
void upsertNode_rejectsEmptyString() {
    assertThrows( IllegalArgumentException.class,
            () -> service.upsertNode( "Raft", "", "PaxosAndRaft",
                    Provenance.HUMAN_AUTHORED, Map.of() ) );
}

@Test
void upsertNode_rejectsUppercaseFirstLetter() {
    assertThrows( IllegalArgumentException.class,
            () -> service.upsertNode( "Foo", "Product", "Bar",
                    Provenance.HUMAN_AUTHORED, Map.of() ) );
}

@Test
void upsertNode_allowsHyphenatedTypes() {
    when( nodes.upsertNode( eq( "X" ), eq( "implementation-plan" ), any(), any(), any() ) )
            .thenReturn( Mockito.mock( KgNode.class ) );
    assertDoesNotThrow( () -> service.upsertNode( "X", "implementation-plan", "Page",
            Provenance.HUMAN_AUTHORED, Map.of() ) );
}

@Test
void upsertNode_allowsLowercaseAlphanumericUnderscore() {
    when( nodes.upsertNode( eq( "X" ), eq( "concept" ), any(), any(), any() ) )
            .thenReturn( Mockito.mock( KgNode.class ) );
    assertDoesNotThrow( () -> service.upsertNode( "X", "concept", "Page",
            Provenance.HUMAN_AUTHORED, Map.of() ) );
}

@Test
void upsertNode_allowsNullNodeType() {
    when( nodes.upsertNode( eq( "X" ), eq( null ), any(), any(), any() ) )
            .thenReturn( Mockito.mock( KgNode.class ) );
    assertDoesNotThrow( () -> service.upsertNode( "X", null, "Page",
            Provenance.HUMAN_AUTHORED, Map.of() ) );
}
```

- [ ] **Step 2: Run, expect 3 failures (typo, empty, uppercase tests fail; positive cases may also fail if existing logic differs)**

Run: `mvn -pl wikantik-main -Dtest=DefaultKnowledgeGraphServiceTest test 2>&1 | tail -15`

- [ ] **Step 3: Add the regex validation**

In `DefaultKnowledgeGraphService.java`, add a static final pattern (near the top of the class):

```java
private static final java.util.regex.Pattern NODE_TYPE_REGEX =
        java.util.regex.Pattern.compile( "^[a-z][a-z0-9_-]{0,30}$" );
```

Find `upsertNode(name, nodeType, sourcePage, provenance, properties)`. Prepend the validation:

```java
@Override
public KgNode upsertNode( final String name, final String nodeType, final String sourcePage,
                          final Provenance provenance, final Map< String, Object > properties ) {
    if ( nodeType != null && !NODE_TYPE_REGEX.matcher( nodeType ).matches() ) {
        throw new IllegalArgumentException(
                "invalid node_type: must match /^[a-z][a-z0-9_-]{0,30}$/ (got: '" + nodeType + "')" );
    }
    final KgNode result = nodes.upsertNode( name, nodeType, sourcePage, provenance, properties );
    snapshotBuilder.invalidateCache();
    return result;
}
```

- [ ] **Step 4: Add symmetric validation to `ProposeKnowledgeTool`**

In `wikantik-admin-mcp/src/main/java/com/wikantik/mcp/tools/ProposeKnowledgeTool.java`, find where it pulls `node_type` from the proposal input and forwards to `service.proposeKnowledge(...)`. Before forwarding, validate:

```java
private static final java.util.regex.Pattern NODE_TYPE_REGEX =
        java.util.regex.Pattern.compile( "^[a-z][a-z0-9_-]{0,30}$" );

// inside execute(...), where new-node proposals are handled:
final String nodeType = McpToolUtils.getString( proposedData, "node_type" );
if ( nodeType != null && !NODE_TYPE_REGEX.matcher( nodeType ).matches() ) {
    return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON,
            "invalid node_type: must match /^[a-z][a-z0-9_-]{0,30}$/ (got: '" + nodeType + "')" );
}
```

Add a corresponding test in `ProposeKnowledgeToolTest.java`:

```java
@Test
void rejectsProposalWithTrailingCommaNodeType() {
    final McpSchema.CallToolResult r = tool.execute( Map.of(
            "proposal_type", "new-node",
            "source_page", "Foo",
            "proposed_data", Map.of( "name", "Bar", "node_type", "concept," ),
            "confidence", 0.9, "reasoning", "x" ) );
    Assertions.assertTrue( r.isError() );
}
```

- [ ] **Step 5: Add an IT in `KgCurationIT`**

Append to `KgCurationIT.java`:

```java
@Test
public void curateNodesUpsertWithPollutedTypeIsPerOpError() {
    final McpSchema.CallToolResult r = mcp.callTool( "curate_nodes",
            Map.of( "operations", List.of( Map.of(
                    "action", "upsert", "tag", "polluted",
                    "name", "PollutionTest_" + System.currentTimeMillis(),
                    "node_type", "concept,",
                    "source_page", "Main" ) ) ) );

    Assertions.assertFalse( r.isError() );
    final String body = ( ( McpSchema.TextContent ) r.content().get( 0 ) ).text();
    Assertions.assertTrue( body.contains( "\"failed\"" ), body );
    Assertions.assertTrue( body.contains( "invalid node_type" ), body );
}
```

- [ ] **Step 6: Run all unit + targeted IT**

```
mvn -pl wikantik-main,wikantik-admin-mcp -am test 2>&1 | tail -10
mvn -pl wikantik-it-tests/wikantik-selenide-tests install -DskipTests -q
mvn -pl wikantik-it-tests/wikantik-it-test-custom -Pintegration-tests -Dit.test='KgCurationIT' verify 2>&1 | grep -E "Tests run|BUILD" | tail -3
```

Expected: BUILD SUCCESS.

- [ ] **Step 7: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/knowledge/DefaultKnowledgeGraphService.java \
        wikantik-main/src/test/java/com/wikantik/knowledge/DefaultKnowledgeGraphServiceTest.java \
        wikantik-admin-mcp/src/main/java/com/wikantik/mcp/tools/ProposeKnowledgeTool.java \
        wikantik-admin-mcp/src/test/java/com/wikantik/mcp/tools/ProposeKnowledgeToolTest.java \
        wikantik-it-tests/wikantik-selenide-tests/src/main/java/com/wikantik/its/mcp/KgCurationIT.java
git commit -m "feat(kg): node_type vocabulary regex at upsertNode + propose_knowledge"
```

---

## Task 7: Helpful error on `curate_nodes` / `curate_edges` nested-shape misuse

**Files:**
- Modify: `wikantik-admin-mcp/src/main/java/com/wikantik/mcp/tools/CurateNodesTool.java`
- Modify: `wikantik-admin-mcp/src/main/java/com/wikantik/mcp/tools/CurateEdgesTool.java`
- Modify: `wikantik-admin-mcp/src/test/java/com/wikantik/mcp/tools/CurateNodesToolTest.java`
- Modify: `wikantik-admin-mcp/src/test/java/com/wikantik/mcp/tools/CurateEdgesToolTest.java`

- [ ] **Step 1: Write failing unit tests**

Append to `CurateNodesToolTest.java`:

```java
@Test
void upsertRejectsNestedNodeShapeWithGuidance() {
    final McpSchema.CallToolResult r = tool.execute( Map.of(
            "operations", List.of( Map.of(
                    "action", "upsert", "tag", "bad",
                    "node", Map.of( "name", "X", "node_type", "concept" ) ) ) ) );
    final String body = ( ( McpSchema.TextContent ) r.content().get( 0 ) ).text();
    Assertions.assertTrue( body.contains( "top level" ),
            "Error should explain top-level shape: " + body );
    Assertions.assertTrue( body.contains( "not nested under 'node'" ), body );
}
```

Append to `CurateEdgesToolTest.java`:

```java
@Test
void upsertRejectsNestedEdgeShapeWithGuidance() {
    final McpSchema.CallToolResult r = tool.execute( Map.of(
            "operations", List.of( Map.of(
                    "action", "upsert", "tag", "bad",
                    "edge", Map.of( "source_id", UUID.randomUUID().toString(),
                                     "target_id", UUID.randomUUID().toString(),
                                     "relationship_type", "rel" ) ) ) ) );
    final String body = ( ( McpSchema.TextContent ) r.content().get( 0 ) ).text();
    Assertions.assertTrue( body.contains( "top level" ), body );
    Assertions.assertTrue( body.contains( "not nested under 'edge'" ), body );
}
```

- [ ] **Step 2: Run, expect fail**

```
mvn -pl wikantik-admin-mcp -Dtest=CurateNodesToolTest,CurateEdgesToolTest test 2>&1 | tail -10
```

- [ ] **Step 3: Add the nested-shape detection**

In `CurateNodesTool.java`, find `doUpsert(Map<String,Object> op)`. Prepend:

```java
private Map< String, Object > doUpsert( final Map< String, Object > op ) {
    if ( op.containsKey( "node" ) ) {
        return Map.of( "error",
            "upsert fields belong at the top level of the operation, not nested under 'node'. "
            + "Expected shape: {action: 'upsert', name: '...', node_type: '...', source_page: '...'}" );
    }
    // existing body unchanged from here
    final String name = stringOrNull( op.get( "name" ) );
    if ( name == null || name.isBlank() ) return Map.of( "error", "upsert requires name" );
    // ...
}
```

In `CurateEdgesTool.java`, similarly:

```java
private Map< String, Object > doUpsert( final Map< String, Object > op ) {
    if ( op.containsKey( "edge" ) ) {
        return Map.of( "error",
            "upsert fields belong at the top level of the operation, not nested under 'edge'. "
            + "Expected shape: {action: 'upsert', source_id: '...', target_id: '...', relationship_type: '...'}" );
    }
    // existing body unchanged
    ...
}
```

- [ ] **Step 4: Run, expect pass**

```
mvn -pl wikantik-admin-mcp -Dtest=CurateNodesToolTest,CurateEdgesToolTest test 2>&1 | tail -10
```

Expected: all tests pass.

- [ ] **Step 5: Commit**

```bash
git add wikantik-admin-mcp/src/main/java/com/wikantik/mcp/tools/CurateNodesTool.java \
        wikantik-admin-mcp/src/main/java/com/wikantik/mcp/tools/CurateEdgesTool.java \
        wikantik-admin-mcp/src/test/java/com/wikantik/mcp/tools/CurateNodesToolTest.java \
        wikantik-admin-mcp/src/test/java/com/wikantik/mcp/tools/CurateEdgesToolTest.java
git commit -m "fix(mcp): curate_{nodes,edges}.upsert returns helpful error on nested shape"
```

---

## Task 8: Wire-level visibility IT

**Files:**
- Create: `wikantik-it-tests/wikantik-selenide-tests/src/main/java/com/wikantik/its/mcp/KgCurationVisibilityIT.java`
- Modify: `wikantik-it-tests/wikantik-selenide-tests/src/main/java/com/wikantik/its/mcp/WithMcpTestSetup.java` — add seed accessors
- Modify: `wikantik-it-tests/src/main/resources/sql/it-test-seed.sql` — add visibility fixtures

- [ ] **Step 1: Add the seed rows**

Append to `wikantik-it-tests/src/main/resources/sql/it-test-seed.sql`:

```sql
-- Visibility test fixtures for KgCurationVisibilityIT
INSERT INTO kg_excluded_pages (page_name, reason)
VALUES ('KgVisibilityExcludedPage', 'visibility-test')
ON CONFLICT (page_name) DO NOTHING;

INSERT INTO kg_nodes (id, name, node_type, source_page, provenance, properties, created, updated)
VALUES (
    'ffffffff-0001-0000-0000-000000000001',
    'KgVisibilityExcludedNode',
    'concept',
    'KgVisibilityExcludedPage',
    'human_authored',
    '{}'::jsonb,
    NOW(), NOW()
) ON CONFLICT (id) DO NOTHING;

INSERT INTO kg_nodes (id, name, node_type, source_page, provenance, properties, created, updated)
VALUES (
    'ffffffff-0002-0000-0000-000000000002',
    'KgVisibilityAllowedNode',
    'concept',
    'KgVisibilityAllowedPage',
    'human_authored',
    '{}'::jsonb,
    NOW(), NOW()
) ON CONFLICT (id) DO NOTHING;
```

Verify the existing schema column names by reading another KG-node INSERT in the same file or in `bin/db/migrations/`. If `provenance` is stored differently (e.g., `HUMAN_AUTHORED` upper-case enum vs `human_authored` string), match the convention.

- [ ] **Step 2: Add seed accessors to `WithMcpTestSetup`**

Append to `wikantik-it-tests/wikantik-selenide-tests/src/main/java/com/wikantik/its/mcp/WithMcpTestSetup.java`:

```java
public static final String SEEDED_VISIBILITY_EXCLUDED_NODE_ID = "ffffffff-0001-0000-0000-000000000001";
public static final String SEEDED_VISIBILITY_ALLOWED_NODE_ID  = "ffffffff-0002-0000-0000-000000000002";
public static final String SEEDED_VISIBILITY_EXCLUDED_NAME    = "KgVisibilityExcludedNode";
public static final String SEEDED_VISIBILITY_ALLOWED_NAME     = "KgVisibilityAllowedNode";

public static String seededVisibilityExcludedNodeId() { return SEEDED_VISIBILITY_EXCLUDED_NODE_ID; }
public static String seededVisibilityAllowedNodeId()  { return SEEDED_VISIBILITY_ALLOWED_NODE_ID; }
```

- [ ] **Step 3: Create the IT**

Create `wikantik-it-tests/wikantik-selenide-tests/src/main/java/com/wikantik/its/mcp/KgCurationVisibilityIT.java`:

```java
/*
    ... standard ASF header
 */
package com.wikantik.its.mcp;

import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/**
 * Wire-level coverage for the admin-bypass on read paths. Seeds one node
 * on an excluded source page and one on an allowed source page; asserts
 * the admin-server query_nodes sees both, the knowledge-server query_nodes
 * sees only the allowed one. See spec
 * docs/superpowers/specs/2026-05-14-kg-curation-operability-design.md §Fix 1.
 */
public class KgCurationVisibilityIT extends WithMcpTestSetup {

    @Test
    public void adminMcpQueryNodesSeesEntitiesOnExcludedPages() {
        final McpSchema.CallToolResult r = mcp.callTool( "query_nodes",
                Map.of( "filters",
                        Map.of( "name", WithMcpTestSetup.SEEDED_VISIBILITY_EXCLUDED_NAME ),
                        "limit", 10 ) );
        Assertions.assertFalse( r.isError() );
        final String body = ( ( McpSchema.TextContent ) r.content().get( 0 ) ).text();
        Assertions.assertTrue( body.contains( WithMcpTestSetup.SEEDED_VISIBILITY_EXCLUDED_NAME ),
                "Admin query_nodes must see entity on excluded page: " + body );
    }

    @Test
    public void adminMcpSearchKnowledgeSeesEntitiesOnExcludedPages() {
        final McpSchema.CallToolResult r = mcp.callTool( "search_knowledge",
                Map.of( "query", "KgVisibilityExclude", "limit", 10 ) );
        Assertions.assertFalse( r.isError() );
        final String body = ( ( McpSchema.TextContent ) r.content().get( 0 ) ).text();
        Assertions.assertTrue( body.contains( WithMcpTestSetup.SEEDED_VISIBILITY_EXCLUDED_NAME ), body );
    }

    @Test
    public void adminMcpQueryNodesSeesAllowedEntities() {
        final McpSchema.CallToolResult r = mcp.callTool( "query_nodes",
                Map.of( "filters",
                        Map.of( "name", WithMcpTestSetup.SEEDED_VISIBILITY_ALLOWED_NAME ),
                        "limit", 10 ) );
        Assertions.assertFalse( r.isError() );
        final String body = ( ( McpSchema.TextContent ) r.content().get( 0 ) ).text();
        Assertions.assertTrue( body.contains( WithMcpTestSetup.SEEDED_VISIBILITY_ALLOWED_NAME ), body );
    }

    // Note: a symmetric test against /knowledge-mcp (the agent-facing server)
    // confirming it does NOT see the excluded node would belong here, but
    // KgCurationIT and WithMcpTestSetup are wired to /wikantik-admin-mcp by
    // default. If a /knowledge-mcp test scaffold exists in this repo, add the
    // cross-check there. Otherwise, the inverse case is unit-tested via
    // KgNodeRepositoryBypassTest.
}
```

- [ ] **Step 4: Run targeted IT to confirm seed + assertions work**

```
mvn -pl wikantik-it-tests/wikantik-selenide-tests install -DskipTests -q
mvn -pl wikantik-it-tests/wikantik-it-test-custom -Pintegration-tests -Dit.test='KgCurationVisibilityIT' verify 2>&1 | grep -E "Tests run|BUILD" | tail -3
```

Expected: BUILD SUCCESS, 3 tests pass.

- [ ] **Step 5: Commit**

```bash
git add wikantik-it-tests/wikantik-selenide-tests/src/main/java/com/wikantik/its/mcp/KgCurationVisibilityIT.java \
        wikantik-it-tests/wikantik-selenide-tests/src/main/java/com/wikantik/its/mcp/WithMcpTestSetup.java \
        wikantik-it-tests/src/main/resources/sql/it-test-seed.sql
git commit -m "test(it): wire-level visibility IT proves admin-bypass on query_nodes + search_knowledge"
```

---

## Task 9: One-shot operator cleanup script + bin docs

**Files:**
- Create: `bin/kg-cleanup-node-types.sh`
- Modify: `CLAUDE.md` (optional — add to bin/ script conventions section if you maintain one)

- [ ] **Step 1: Create the script**

Create `bin/kg-cleanup-node-types.sh`:

```bash
#!/usr/bin/env bash
# kg-cleanup-node-types.sh — interactive one-shot to clean legacy
# node_type pollution from kg_nodes.
#
# WHY ONE-SHOT, NOT A MIGRATION:
#   Per docs/superpowers/specs/2026-05-14-kg-curation-operability-design.md
#   and the project memory feedback_no_data_backfill_in_migrations, data
#   fixups never land in bin/db/migrations/. They live here so the operator
#   runs them once per environment.
#
# Run AFTER the service-tier vocabulary gate ships
# (^[a-z][a-z0-9_-]{0,30}$). New writes are rejected at the boundary; this
# script normalizes the legacy data that predates the gate.
#
# USAGE:
#   bin/kg-cleanup-node-types.sh                 # uses ROOT.xml credentials
#   PGHOST=db.example.com PGUSER=postgres \\
#     PGPASSWORD=... PGDATABASE=jspwiki bin/kg-cleanup-node-types.sh

set -euo pipefail

# Resolve DB credentials. Prefer env, fall back to local Tomcat ROOT.xml.
ROOTXML="tomcat/tomcat-11/conf/Catalina/localhost/ROOT.xml"
if [[ -z "${PGHOST:-}" ]]; then PGHOST="localhost"; fi
if [[ -z "${PGUSER:-}" ]]; then
    PGUSER=$(awk -F'"' '/username=/{print $2; exit}' "$ROOTXML" 2>/dev/null || echo "jspwiki")
fi
if [[ -z "${PGPASSWORD:-}" ]]; then
    PGPASSWORD=$(awk -F'"' '/password=/{print $2; exit}' "$ROOTXML" 2>/dev/null || echo "")
fi
if [[ -z "${PGDATABASE:-}" ]]; then PGDATABASE="jspwiki"; fi
export PGHOST PGUSER PGPASSWORD PGDATABASE

REGEX='^[a-z][a-z0-9_-]{0,30}$'

echo "=== Current node_type distribution ==="
psql -A -t -F'|' -c "SELECT node_type, COUNT(*) FROM kg_nodes GROUP BY node_type ORDER BY 2 DESC"

echo
echo "=== Non-matching types (vocabulary regex: $REGEX) ==="
mapfile -t BAD < <(psql -A -t -c \
    "SELECT node_type FROM kg_nodes GROUP BY node_type
     HAVING node_type IS NULL OR node_type !~ '$REGEX'")

if (( ${#BAD[@]} == 0 )); then
    echo "(none — corpus is clean)"
    exit 0
fi

for ROW in "${BAD[@]}"; do
    if [[ -z "$ROW" ]]; then
        CURRENT="(empty string)"
        WHERE="node_type = ''"
    else
        CURRENT="$ROW"
        WHERE="node_type = '$(echo "$ROW" | sed "s/'/''/g")'"
    fi

    COUNT=$(psql -A -t -c "SELECT COUNT(*) FROM kg_nodes WHERE $WHERE")
    echo
    echo "Polluted type: '$CURRENT'  ($COUNT rows)"
    read -r -p "  [r]ename to, [d]elete rows, [s]kip ? " ACTION

    case "$ACTION" in
        r)
            read -r -p "  Rename to: " REPLACEMENT
            psql -c "UPDATE kg_nodes SET node_type = '$(echo "$REPLACEMENT" | sed "s/'/''/g")' WHERE $WHERE"
            ;;
        d)
            read -r -p "  Confirm delete $COUNT rows? (yes/no): " CONFIRM
            if [[ "$CONFIRM" == "yes" ]]; then
                psql -c "DELETE FROM kg_nodes WHERE $WHERE"
            else
                echo "  skipped (no confirmation)"
            fi
            ;;
        *)
            echo "  skipped"
            ;;
    esac
done

echo
echo "=== Post-cleanup distribution ==="
psql -A -t -F'|' -c "SELECT node_type, COUNT(*) FROM kg_nodes GROUP BY node_type ORDER BY 2 DESC"

echo
echo "Done. Verify with: bin/kg-cleanup-node-types.sh (should report no pollution)."
```

- [ ] **Step 2: Make it executable**

```bash
chmod +x bin/kg-cleanup-node-types.sh
```

- [ ] **Step 3: Smoke-test the help/usage path**

```bash
bin/kg-cleanup-node-types.sh --help 2>&1 | head -5
```

(The script doesn't have a `--help` flag yet — verify the header docstring is visible if you grep it. Or add `if [[ "${1:-}" == "--help" ]]; then sed -n '1,30p' "$0"; exit 0; fi` near the top, matching the convention noted in `CLAUDE.md`'s "bin/ script conventions" section.)

- [ ] **Step 4: (Optional) Add `--help` flag per CLAUDE.md convention**

Insert near the top of the script after `set -euo pipefail`:

```bash
if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
    sed -n '2,30p' "$0" | sed 's/^# \?//'
    exit 0
fi
```

- [ ] **Step 5: Commit**

```bash
git add bin/kg-cleanup-node-types.sh
git commit -m "chore(ops): one-shot script to clean legacy node_type pollution"
```

---

## Task 10: Documentation updates

**Files:**
- Modify: `CLAUDE.md` — tool count 22 → 24
- Modify: `docs/wikantik-pages/KgInclusionPolicy.md` — admin-bypass note

- [ ] **Step 1: Update `CLAUDE.md`**

In the Agent-facing surface summary table, change:

```
| `/wikantik-admin-mcp` | wikantik-admin-mcp | MCP (Streamable HTTP) | 22 write/analytics tools (incl. KG curation) | ...
```

to:

```
| `/wikantik-admin-mcp` | wikantik-admin-mcp | MCP (Streamable HTTP) | 24 write/analytics tools (incl. KG curation + admin-bypass reads) | ...
```

Also update the inline prose mention ("22 tools (incl. inspect_proposals, review_proposals, curate_edges, curate_nodes) — reconciled 2026-05-13") to:

```
24 tools — adds admin-bypass mirrors of query_nodes + search_knowledge so
curators see freshly-created entities. Reconciled 2026-05-14.
```

- [ ] **Step 2: Update `KgInclusionPolicy.md`**

In `docs/wikantik-pages/KgInclusionPolicy.md`, locate the "Agent curation path" section (added by the KG curation work). Append:

```markdown
### Admin-bypass on read paths

Admin-context reads bypass the inclusion filter so curators see entities
they just created, even when the source page hasn't been admitted by the
cluster policy yet. The bypass applies to:

- REST `/admin/knowledge-graph/*` reads (already gated by `AdminAuthFilter`).
- The MCP tools registered on `/wikantik-admin-mcp` — `list_proposals`,
  `inspect_proposals`, and the new admin-bypass copies of `query_nodes`
  and `search_knowledge` (24 tools total).

The agent-facing `/knowledge-mcp` server keeps the filter on, so retrieval
quality is unchanged. See
`docs/superpowers/specs/2026-05-14-kg-curation-operability-design.md`
for the full contract.
```

- [ ] **Step 3: Commit**

```bash
git add CLAUDE.md docs/wikantik-pages/KgInclusionPolicy.md
git commit -m "docs(kg): document admin-bypass on read paths + bump tool count to 24"
```

---

## Task 11: Final verification

**Files:** none (build verification + script smoke-test)

- [ ] **Step 1: Full unit reactor (serial — `-T 1C` races on this repo)**

Run: `mvn clean install -DskipITs 2>&1 | grep -E "BUILD SUCCESS|BUILD FAILURE" | tail -3`
Expected: `BUILD SUCCESS`.

- [ ] **Step 2: Full integration test reactor (must be sequential per CLAUDE.md)**

Run: `mvn clean install -Pintegration-tests -fae 2>&1 | grep -E "BUILD SUCCESS|BUILD FAILURE" | tail -3`
Expected: `BUILD SUCCESS`. If any test fails, fix it; never add `@Disabled`.

- [ ] **Step 3: Verify the live MCP tool count is 24**

Once Tomcat is running locally:

```bash
source <(grep -v '^#' test.properties | sed 's/^test.user.//' | sed 's/=/="/' | sed 's/$/"/')
# replace KEY below with the actual /wikantik-admin-mcp access key from
# tomcat/tomcat-11/lib/wikantik-custom.properties
curl -s -H "Authorization: Bearer $KEY" \
     -H 'Content-Type: application/json' \
     -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}' \
     http://localhost:8080/wikantik-admin-mcp | jq '.result.tools | length'
```

Expected: `24`.

- [ ] **Step 4: Run the cleanup script against the live local DB and clean the actual pollution**

```bash
bin/kg-cleanup-node-types.sh
```

Interactive — handle:
- 71 empty-string rows: rename to `concept` (most common type) OR delete; operator's choice
- 2 `concept,` rows: rename to `concept`
- 1 `Product` row: rename to `product`
- 1 `not_a_valid_type_hopefully` row: delete (test injection)

Re-run the script to verify the distribution is clean.

- [ ] **Step 5: Update the memory pointer** (optional but recommended)

If memory `project_admin_mcp_tool_surface.md` exists, update the tool count from 22 to 24 and note the two new admin-bypass reads.

---

## Self-Review

Spec coverage:

| Spec section | Plan task |
|--------------|-----------|
| Fix 1 — KgInclusionFilter accessors | Task 1 |
| Fix 1 — KgNodeRepository + KnowledgeGraphService overloads | Task 2 |
| Fix 1 — REST + curator MCP read paths | Task 3 |
| Fix 1 — `/wikantik-admin-mcp` registers query_nodes + search_knowledge | Task 4 |
| Fix 1 — visibility IT | Task 8 |
| Fix 2 — refuse ghost merges | Task 5 |
| Fix 3 — node_type regex at service + propose_knowledge | Task 6 |
| Fix 3 — one-shot cleanup script | Task 9 |
| Fix 4 — nested-shape errors on curate_{nodes,edges}.upsert | Task 7 |
| Docs (CLAUDE.md, KgInclusionPolicy.md) | Task 10 |
| Testing matrix (5 unit tests, 4 new ITs) | Tasks 1, 2, 3, 5, 6, 7, 8 |
| Final verification | Task 11 |

All spec sections covered. No placeholders. Type consistency: `adminBypass` flag name is used uniformly; `nodeFilterJoin/Where`, `edgeFilterJoin/Where`, `mentionFilterJoin/Where` accessor names match across Tasks 1 and 2; method signatures for `queryNodes/searchKnowledge/getNode/getNodeByName` overloads consistent across the interface, the impl, and the call sites (Tasks 2, 3, 4).
