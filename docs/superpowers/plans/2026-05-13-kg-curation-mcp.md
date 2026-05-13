# KG Curation on MCP Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add bulk-only KG curation tools (`inspect_proposals`, `review_proposals`, `curate_edges`, `curate_nodes`) and enrich `list_proposals` with conflict flags on `/wikantik-admin-mcp`, so curator agents can complete the full triage loop without falling through to REST.

**Architecture:** Spec §3 calls for a single service-level facade shared by REST and MCP. We add `KgCurationOps` (interface in `wikantik-api`) implemented by `DefaultKgCurationOps` (in `wikantik-main`), wrapping the existing `KnowledgeGraphService` primitives with `Optional<String>` error envelopes and the existing `writeFrontmatterIfEdge` side effect. REST's private `try*` helpers and the inline `writeFrontmatterIfEdge` are replaced with calls into this facade. Four new MCP tools sit on the facade. A `ProposalConflictFlags` helper centralises the `node_exists` / `edge_previously_rejected` computation.

**Tech Stack:** Java 21, JUnit 5, Mockito, MCP SDK (`io.modelcontextprotocol`), Maven multi-module reactor, Cargo-launched Tomcat ITs.

**Spec:** `docs/superpowers/specs/2026-05-13-kg-curation-mcp-design.md`

---

## File Structure

**Created files:**

- `wikantik-api/src/main/java/com/wikantik/api/knowledge/KgCurationOps.java` — facade interface (Optional<String> error envelopes per op)
- `wikantik-main/src/main/java/com/wikantik/knowledge/curation/DefaultKgCurationOps.java` — impl, owns `writeFrontmatterIfEdge` and frontmatter rename-on-merge side effects
- `wikantik-knowledge/src/main/java/com/wikantik/knowledge/curation/ProposalConflictFlags.java` — shared conflict-flag computation
- `wikantik-admin-mcp/src/main/java/com/wikantik/mcp/tools/InspectProposalsTool.java`
- `wikantik-admin-mcp/src/main/java/com/wikantik/mcp/tools/ReviewProposalsTool.java`
- `wikantik-admin-mcp/src/main/java/com/wikantik/mcp/tools/CurateEdgesTool.java`
- `wikantik-admin-mcp/src/main/java/com/wikantik/mcp/tools/CurateNodesTool.java`
- Unit tests beside each new class (`*Test.java`)
- `wikantik-it-tests/wikantik-selenide-tests/src/main/java/com/wikantik/its/mcp/KgCurationIT.java` — wire-level Cargo IT covering all four new tools + enriched `list_proposals`

**Modified files:**

- `wikantik-api/.../KnowledgeGraphService.java` — no change. Curation envelopes live on `KgCurationOps`.
- `wikantik-main/.../DefaultKnowledgeGraphService.java` — implements `KgCurationOps` *via* delegation to `DefaultKgCurationOps` (or composition; see Task 1).
- `wikantik-main/.../knowledge/subsystem/KnowledgeSubsystem.java` + `KnowledgeSubsystemBridge.java` — add `KgCurationOps kgCurationOps()` accessor so both REST and MCP can pull it from the engine.
- `wikantik-rest/.../AdminKnowledgeResource.java` — replace private `try*` helpers and inline `writeFrontmatterIfEdge` with `KgCurationOps` calls; route `node_exists` / `edge_previously_rejected` through `ProposalConflictFlags`.
- `wikantik-admin-mcp/.../tools/ListProposalsTool.java` — call `ProposalConflictFlags` to enrich each proposal payload; update `outputSchema.examples`.
- `wikantik-admin-mcp/.../McpToolRegistry.java` — register the four new tools; pull `KgCurationOps` from `KnowledgeSubsystemBridge`.
- `wikantik-admin-mcp/.../McpConfig.java` — add `kgCurationBulkLimit()` accessor (property `wikantik.mcp.kg_curation.bulk_limit`, default 50).
- `wikantik-admin-mcp/.../tools/McpAudit.java` — add `logBulkWrite(tool, attempted, succeeded, failed, author)` overload.
- `wikantik-it-tests/.../McpProtocolIT.java` — update `EXPECTED_TOOLS` from 18 entries to 22.
- `CLAUDE.md` — bump `/wikantik-admin-mcp` tool count from 18 to 22 in the agent-facing surface table.

---

## Task 1: Introduce `KgCurationOps` facade with proposal-review methods

**Files:**
- Create: `wikantik-api/src/main/java/com/wikantik/api/knowledge/KgCurationOps.java`
- Create: `wikantik-main/src/main/java/com/wikantik/knowledge/curation/DefaultKgCurationOps.java`
- Test: `wikantik-main/src/test/java/com/wikantik/knowledge/curation/DefaultKgCurationOpsTest.java`

- [ ] **Step 1: Write the failing test (3 cases)**

```java
package com.wikantik.knowledge.curation;

import com.wikantik.api.knowledge.KgProposal;
import com.wikantik.api.knowledge.KnowledgeGraphService;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.pages.PageSaveHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

public class DefaultKgCurationOpsTest {

    private KnowledgeGraphService kg;
    private PageManager pages;
    private PageSaveHelper saver;
    private DefaultKgCurationOps ops;

    @BeforeEach
    void setUp() {
        kg = Mockito.mock( KnowledgeGraphService.class );
        pages = Mockito.mock( PageManager.class );
        saver = Mockito.mock( PageSaveHelper.class );
        ops = new DefaultKgCurationOps( kg, pages, saver );
    }

    @Test
    void approveReturnsEmptyOptionalOnSuccess() {
        final UUID id = UUID.randomUUID();
        final KgProposal approved = Mockito.mock( KgProposal.class );
        when( approved.proposalType() ).thenReturn( "new-node" );
        when( kg.approveProposal( eq( id ), eq( "alice" ) ) ).thenReturn( approved );

        assertEquals( Optional.empty(), ops.tryApproveProposal( id, "alice" ) );
    }

    @Test
    void approveReturnsNotFoundWhenServiceReturnsNull() {
        final UUID id = UUID.randomUUID();
        when( kg.approveProposal( eq( id ), eq( "alice" ) ) ).thenReturn( null );

        final Optional<String> result = ops.tryApproveProposal( id, "alice" );
        assertTrue( result.isPresent() );
        assertTrue( result.get().contains( "Not found" ) );
    }

    @Test
    void approveSurfacesServiceExceptionMessage() {
        final UUID id = UUID.randomUUID();
        when( kg.approveProposal( eq( id ), any() ) )
                .thenThrow( new RuntimeException( "constraint violation" ) );

        final Optional<String> result = ops.tryApproveProposal( id, "alice" );
        assertTrue( result.isPresent() );
        assertEquals( "constraint violation", result.get() );
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl wikantik-main -Dtest=DefaultKgCurationOpsTest test-compile`
Expected: COMPILE-ERROR (`DefaultKgCurationOps` does not exist).

- [ ] **Step 3: Create the `KgCurationOps` interface**

```java
package com.wikantik.api.knowledge;

import java.util.Optional;
import java.util.UUID;

/**
 * Facade for Knowledge Graph curation operations shared by the REST admin surface
 * and the /wikantik-admin-mcp MCP tools. Each method returns {@code Optional.empty()}
 * on success or {@code Optional.of(errorMessage)} on per-op failure — callers use
 * this to assemble bulk-result envelopes without throwing.
 *
 * <p>Implementations route through {@link KnowledgeGraphService} for the underlying
 * write operation and own any required side effects (e.g. frontmatter write-back on
 * edge-proposal approval). Both REST and MCP MUST call into this facade so the two
 * surfaces cannot drift.
 */
public interface KgCurationOps {

    Optional<String> tryApproveProposal( UUID proposalId, String reviewedBy );

    Optional<String> tryRejectProposal( UUID proposalId, String reviewedBy, String reason );

    Optional<String> tryJudgeProposal( UUID proposalId, String reviewedBy );
}
```

- [ ] **Step 4: Create the `DefaultKgCurationOps` impl (proposal methods only)**

```java
package com.wikantik.knowledge.curation;

import com.wikantik.api.knowledge.KgCurationOps;
import com.wikantik.api.knowledge.KgProposal;
import com.wikantik.api.knowledge.KnowledgeGraphService;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.pages.PageSaveHelper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Optional;
import java.util.UUID;

public class DefaultKgCurationOps implements KgCurationOps {

    private static final Logger LOG = LogManager.getLogger( DefaultKgCurationOps.class );

    private final KnowledgeGraphService kg;
    private final PageManager pages;
    private final PageSaveHelper saver;

    public DefaultKgCurationOps( final KnowledgeGraphService kg,
                                 final PageManager pages,
                                 final PageSaveHelper saver ) {
        this.kg = kg;
        this.pages = pages;
        this.saver = saver;
    }

    @Override
    public Optional<String> tryApproveProposal( final UUID proposalId, final String reviewedBy ) {
        try {
            final KgProposal approved = kg.approveProposal( proposalId, reviewedBy );
            if ( approved == null ) {
                return Optional.of( "Not found: " + proposalId );
            }
            writeFrontmatterIfEdge( approved );
            return Optional.empty();
        } catch ( final Exception e ) {
            LOG.warn( "tryApproveProposal: proposal={} actor={}: {}",
                    proposalId, reviewedBy, e.getMessage() );
            return Optional.of( e.getMessage() != null ? e.getMessage() : "Internal error" );
        }
    }

    @Override
    public Optional<String> tryRejectProposal( final UUID proposalId, final String reviewedBy, final String reason ) {
        try {
            final KgProposal rejected = kg.rejectProposal( proposalId, reviewedBy, reason );
            if ( rejected == null ) {
                return Optional.of( "Not found: " + proposalId );
            }
            return Optional.empty();
        } catch ( final Exception e ) {
            LOG.warn( "tryRejectProposal: proposal={} actor={}: {}",
                    proposalId, reviewedBy, e.getMessage() );
            return Optional.of( e.getMessage() != null ? e.getMessage() : "Internal error" );
        }
    }

    @Override
    public Optional<String> tryJudgeProposal( final UUID proposalId, final String reviewedBy ) {
        try {
            kg.judgeNow( proposalId, reviewedBy );
            return Optional.empty();
        } catch ( final Exception e ) {
            LOG.warn( "tryJudgeProposal: proposal={} actor={}: {}",
                    proposalId, reviewedBy, e.getMessage() );
            return Optional.of( e.getMessage() != null ? e.getMessage() : "Judge error" );
        }
    }

    /**
     * After approving a {@code new-edge} proposal, writes the approved relationship
     * back into the source page's frontmatter. Body lifted from {@code AdminKnowledgeResource}.
     */
    void writeFrontmatterIfEdge( final KgProposal approved ) {
        // Filled in by Task 2 — placeholder no-op for now.
    }
}
```

- [ ] **Step 5: Run test, expect pass**

Run: `mvn -pl wikantik-main -Dtest=DefaultKgCurationOpsTest test`
Expected: BUILD SUCCESS; 3 tests pass.

- [ ] **Step 6: Commit**

```bash
git add wikantik-api/src/main/java/com/wikantik/api/knowledge/KgCurationOps.java \
        wikantik-main/src/main/java/com/wikantik/knowledge/curation/DefaultKgCurationOps.java \
        wikantik-main/src/test/java/com/wikantik/knowledge/curation/DefaultKgCurationOpsTest.java
git commit -m "feat(kg-curation): KgCurationOps facade with proposal review methods"
```

---

## Task 2: Move `writeFrontmatterIfEdge` side effect into the facade

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/knowledge/curation/DefaultKgCurationOps.java`
- Modify: `wikantik-main/src/test/java/com/wikantik/knowledge/curation/DefaultKgCurationOpsTest.java`

- [ ] **Step 1: Add a failing test asserting the side effect fires on edge approval**

Append to `DefaultKgCurationOpsTest`:

```java
@Test
void approvingNewEdgeWritesBackToSourcePageFrontmatter() throws Exception {
    final UUID id = UUID.randomUUID();
    final KgProposal approved = Mockito.mock( KgProposal.class );
    when( approved.proposalType() ).thenReturn( "new-edge" );
    when( approved.sourcePage() ).thenReturn( "HybridRetrieval" );
    when( approved.proposedData() ).thenReturn( java.util.Map.of(
            "target", "BM25", "relationship", "falls_back_to" ) );
    when( kg.approveProposal( eq( id ), any() ) ).thenReturn( approved );
    when( pages.getPureText( eq( "HybridRetrieval" ), Mockito.anyInt() ) )
            .thenReturn( "---\ntitle: Hybrid Retrieval\n---\nbody" );

    ops.tryApproveProposal( id, "alice" );

    Mockito.verify( saver ).saveText( eq( "HybridRetrieval" ), Mockito.contains( "falls_back_to" ),
            any( com.wikantik.api.pages.SaveOptions.class ) );
}

@Test
void approvingNonEdgeProposalDoesNotTouchFrontmatter() {
    final UUID id = UUID.randomUUID();
    final KgProposal approved = Mockito.mock( KgProposal.class );
    when( approved.proposalType() ).thenReturn( "new-node" );
    when( kg.approveProposal( eq( id ), any() ) ).thenReturn( approved );

    ops.tryApproveProposal( id, "alice" );

    Mockito.verifyNoInteractions( saver );
}
```

- [ ] **Step 2: Run, expect fail**

Run: `mvn -pl wikantik-main -Dtest=DefaultKgCurationOpsTest test`
Expected: 2 new tests fail (saver never invoked).

- [ ] **Step 3: Lift `writeFrontmatterIfEdge` body from `AdminKnowledgeResource.java:1006-1054`**

Replace the placeholder in `DefaultKgCurationOps.writeFrontmatterIfEdge`:

```java
@SuppressWarnings( "unchecked" )
void writeFrontmatterIfEdge( final KgProposal proposal ) {
    if ( !"new-edge".equals( proposal.proposalType() ) || proposal.sourcePage() == null ) return;
    final java.util.Map< String, Object > data = proposal.proposedData();
    if ( data == null ) return;
    final String target = ( String ) data.get( "target" );
    final String relationship = ( String ) data.get( "relationship" );
    if ( target == null || relationship == null ) return;

    try {
        final String pageName = proposal.sourcePage().replace( ".md", "" );
        final String pageText = pages.getPureText( pageName,
                com.wikantik.api.providers.PageProvider.LATEST_VERSION );
        if ( pageText == null ) {
            LOG.warn( "Cannot write-back to page '{}': page not found", pageName );
            return;
        }
        final com.wikantik.frontmatter.ParsedPage parsed =
                com.wikantik.frontmatter.FrontmatterParser.parse( pageText );
        final java.util.Map< String, Object > metadata =
                new java.util.LinkedHashMap<>( parsed.metadata() );
        final Object existing = metadata.get( relationship );
        if ( existing instanceof java.util.List ) {
            final java.util.List< String > list =
                    new java.util.ArrayList<>( ( java.util.List< String > ) existing );
            if ( !list.contains( target ) ) list.add( target );
            metadata.put( relationship, list );
        } else {
            metadata.put( relationship, new java.util.ArrayList<>( java.util.List.of( target ) ) );
        }
        final String updated = com.wikantik.frontmatter.FrontmatterWriter.write(
                metadata, parsed.body() );
        final com.wikantik.api.pages.SaveOptions opts =
                com.wikantik.api.pages.SaveOptions.builder()
                        .author( "Knowledge Admin" )
                        .changeNote( "Approved knowledge proposal: "
                                + relationship + " → " + target )
                        .build();
        saver.saveText( pageName, updated, opts );
        LOG.info( "Frontmatter write-back: added {} → {} to page '{}'",
                relationship, target, pageName );
    } catch ( final com.wikantik.api.exceptions.WikiException e ) {
        LOG.error( "Failed to write-back frontmatter for proposal {}: {}",
                proposal.id(), e.getMessage(), e );
    }
}
```

- [ ] **Step 4: Run, expect pass**

Run: `mvn -pl wikantik-main -Dtest=DefaultKgCurationOpsTest test`
Expected: 5/5 tests pass.

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/knowledge/curation/DefaultKgCurationOps.java \
        wikantik-main/src/test/java/com/wikantik/knowledge/curation/DefaultKgCurationOpsTest.java
git commit -m "feat(kg-curation): move edge-approval frontmatter write-back into facade"
```

---

## Task 3: Add edge-curation methods to the facade

**Files:**
- Modify: `wikantik-api/.../KgCurationOps.java`
- Modify: `wikantik-main/.../DefaultKgCurationOps.java`
- Modify: `wikantik-main/.../DefaultKgCurationOpsTest.java`

- [ ] **Step 1: Add four failing tests**

Append to `DefaultKgCurationOpsTest`:

```java
@Test
void tryUpsertEdgeReturnsIdOnSuccess() {
    final UUID source = UUID.randomUUID();
    final UUID target = UUID.randomUUID();
    final UUID edgeId = UUID.randomUUID();
    final com.wikantik.api.knowledge.KgEdge edge = Mockito.mock( com.wikantik.api.knowledge.KgEdge.class );
    when( edge.id() ).thenReturn( edgeId );
    when( kg.upsertEdge( eq( source ), eq( target ), eq( "depends_on" ),
            eq( com.wikantik.api.knowledge.Provenance.HUMAN_CURATED ),
            any() ) ).thenReturn( edge );

    final KgCurationOps.EdgeResult r = ops.tryUpsertEdge( source, target, "depends_on",
            java.util.Map.of(), "alice" );
    assertTrue( r.error().isEmpty() );
    assertEquals( edgeId, r.edgeId().orElseThrow() );
}

@Test
void tryUpsertEdgeReportsDuplicateKeyAsErrorMessage() {
    when( kg.upsertEdge( any(), any(), any(), any(), any() ) )
            .thenThrow( new RuntimeException( "duplicate key value violates unique constraint" ) );
    final KgCurationOps.EdgeResult r = ops.tryUpsertEdge(
            UUID.randomUUID(), UUID.randomUUID(), "rel", java.util.Map.of(), "alice" );
    assertTrue( r.error().isPresent() );
    assertTrue( r.error().get().toLowerCase().contains( "duplicate" ) );
}

@Test
void tryConfirmEdgeReturnsNotFoundWhenServiceReturnsNull() {
    final UUID id = UUID.randomUUID();
    when( kg.confirmEdge( eq( id ), any() ) ).thenReturn( null );
    assertTrue( ops.tryConfirmEdge( id, "alice" ).isPresent() );
}

@Test
void tryDeleteAndRejectEdgeSucceedsWhenServiceReturnsCleanly() {
    final UUID id = UUID.randomUUID();
    Mockito.doNothing().when( kg ).deleteEdgeAndRecordRejection( eq( id ), eq( "alice" ), eq( "spurious" ) );
    assertEquals( Optional.empty(), ops.tryDeleteAndRejectEdge( id, "alice", "spurious" ) );
}
```

- [ ] **Step 2: Run, expect fail**

Run: `mvn -pl wikantik-main -Dtest=DefaultKgCurationOpsTest test-compile`
Expected: compile errors (methods + `EdgeResult` not yet defined).

- [ ] **Step 3: Extend `KgCurationOps` interface**

```java
// inside KgCurationOps
Optional<String> tryConfirmEdge( UUID edgeId, String actor );
Optional<String> tryDeleteEdge( UUID edgeId, String actor );
Optional<String> tryDeleteAndRejectEdge( UUID edgeId, String actor, String reason );
EdgeResult tryUpsertEdge( UUID sourceId, UUID targetId, String relationshipType,
                          java.util.Map<String, Object> properties, String actor );

record EdgeResult( Optional<UUID> edgeId, Optional<String> error ) {
    public static EdgeResult ok( UUID id ) { return new EdgeResult( Optional.of( id ), Optional.empty() ); }
    public static EdgeResult fail( String msg ) { return new EdgeResult( Optional.empty(), Optional.of( msg ) ); }
}
```

- [ ] **Step 4: Implement the four methods on `DefaultKgCurationOps`**

```java
@Override
public Optional<String> tryConfirmEdge( final UUID edgeId, final String actor ) {
    try {
        final com.wikantik.api.knowledge.KgEdge after = kg.confirmEdge( edgeId, actor );
        if ( after == null ) return Optional.of( "Edge not found: " + edgeId );
        return Optional.empty();
    } catch ( final Exception e ) {
        LOG.warn( "tryConfirmEdge: edge={} actor={}: {}", edgeId, actor, e.getMessage() );
        return Optional.of( e.getMessage() != null ? e.getMessage() : "Internal error" );
    }
}

@Override
public Optional<String> tryDeleteEdge( final UUID edgeId, final String actor ) {
    try {
        kg.deleteEdge( edgeId );
        return Optional.empty();
    } catch ( final Exception e ) {
        LOG.warn( "tryDeleteEdge: edge={} actor={}: {}", edgeId, actor, e.getMessage() );
        return Optional.of( e.getMessage() != null ? e.getMessage() : "Internal error" );
    }
}

@Override
public Optional<String> tryDeleteAndRejectEdge( final UUID edgeId, final String actor, final String reason ) {
    try {
        kg.deleteEdgeAndRecordRejection( edgeId, actor, reason );
        return Optional.empty();
    } catch ( final Exception e ) {
        LOG.warn( "tryDeleteAndRejectEdge: edge={} actor={}: {}", edgeId, actor, e.getMessage() );
        return Optional.of( e.getMessage() != null ? e.getMessage() : "Internal error" );
    }
}

@Override
public EdgeResult tryUpsertEdge( final UUID sourceId, final UUID targetId, final String rel,
                                  final java.util.Map<String, Object> props, final String actor ) {
    try {
        final com.wikantik.api.knowledge.KgEdge edge = kg.upsertEdge( sourceId, targetId, rel,
                com.wikantik.api.knowledge.Provenance.HUMAN_CURATED,
                props == null ? java.util.Map.of() : props );
        return EdgeResult.ok( edge.id() );
    } catch ( final RuntimeException e ) {
        LOG.warn( "tryUpsertEdge: src={} tgt={} rel={} actor={}: {}",
                sourceId, targetId, rel, actor, e.getMessage() );
        return EdgeResult.fail( e.getMessage() != null ? e.getMessage() : "Internal error" );
    }
}
```

- [ ] **Step 5: Run, expect pass**

Run: `mvn -pl wikantik-main -Dtest=DefaultKgCurationOpsTest test`
Expected: all tests pass.

- [ ] **Step 6: Commit**

```bash
git add wikantik-api/src/main/java/com/wikantik/api/knowledge/KgCurationOps.java \
        wikantik-main/src/main/java/com/wikantik/knowledge/curation/DefaultKgCurationOps.java \
        wikantik-main/src/test/java/com/wikantik/knowledge/curation/DefaultKgCurationOpsTest.java
git commit -m "feat(kg-curation): edge ops on facade (upsert/confirm/delete/delete-and-reject)"
```

---

## Task 4: Add node-curation methods to the facade

**Files:**
- Modify: `wikantik-api/.../KgCurationOps.java`
- Modify: `wikantik-main/.../DefaultKgCurationOps.java`
- Modify: `wikantik-main/.../DefaultKgCurationOpsTest.java`

- [ ] **Step 1: Add three failing tests**

```java
@Test
void tryUpsertNodeReturnsIdOnSuccess() {
    final UUID nodeId = UUID.randomUUID();
    final com.wikantik.api.knowledge.KgNode node =
            Mockito.mock( com.wikantik.api.knowledge.KgNode.class );
    when( node.id() ).thenReturn( nodeId );
    when( kg.upsertNode( eq( "Raft" ), eq( "concept" ), eq( "PaxosAndRaft" ),
            eq( com.wikantik.api.knowledge.Provenance.HUMAN_AUTHORED ),
            any() ) ).thenReturn( node );

    final KgCurationOps.NodeResult r = ops.tryUpsertNode( "Raft", "concept", "PaxosAndRaft",
            java.util.Map.of(), "alice" );
    assertEquals( nodeId, r.nodeId().orElseThrow() );
}

@Test
void tryUpsertNodeFilteredByPolicyReportsConflict() {
    when( kg.upsertNode( any(), any(), any(), any(), any() ) ).thenReturn( null );
    final KgCurationOps.NodeResult r = ops.tryUpsertNode( "Raft", "concept", "Excluded",
            java.util.Map.of(), "alice" );
    assertTrue( r.error().isPresent() );
    assertTrue( r.error().get().contains( "not visible after insert" ) );
}

@Test
void tryMergeNodesRejectsSelfMerge() {
    final UUID id = UUID.randomUUID();
    final Optional<String> r = ops.tryMergeNodes( id, id, "alice" );
    assertTrue( r.isPresent() );
    assertTrue( r.get().toLowerCase().contains( "same" ) );
    Mockito.verifyNoInteractions( kg );  // service must not be called
}
```

- [ ] **Step 2: Run, expect fail**

Run: `mvn -pl wikantik-main -Dtest=DefaultKgCurationOpsTest test-compile`
Expected: compile errors.

- [ ] **Step 3: Extend `KgCurationOps`**

```java
// inside KgCurationOps
Optional<String> tryDeleteNode( UUID nodeId, String actor );
Optional<String> tryMergeNodes( UUID sourceId, UUID targetId, String actor );
NodeResult tryUpsertNode( String name, String nodeType, String sourcePage,
                          java.util.Map<String, Object> properties, String actor );

record NodeResult( Optional<UUID> nodeId, Optional<String> error ) {
    public static NodeResult ok( UUID id ) { return new NodeResult( Optional.of( id ), Optional.empty() ); }
    public static NodeResult fail( String msg ) { return new NodeResult( Optional.empty(), Optional.of( msg ) ); }
}
```

- [ ] **Step 4: Implement on `DefaultKgCurationOps`**

```java
@Override
public Optional<String> tryDeleteNode( final UUID nodeId, final String actor ) {
    try {
        kg.deleteNode( nodeId );
        return Optional.empty();
    } catch ( final Exception e ) {
        LOG.warn( "tryDeleteNode: node={} actor={}: {}", nodeId, actor, e.getMessage() );
        return Optional.of( e.getMessage() != null ? e.getMessage() : "Internal error" );
    }
}

@Override
public Optional<String> tryMergeNodes( final UUID sourceId, final UUID targetId, final String actor ) {
    if ( sourceId == null || targetId == null ) return Optional.of( "source_id and target_id are required" );
    if ( sourceId.equals( targetId ) ) return Optional.of( "source_id and target_id are the same" );
    try {
        kg.mergeNodes( sourceId, targetId );
        return Optional.empty();
    } catch ( final Exception e ) {
        LOG.warn( "tryMergeNodes: src={} tgt={} actor={}: {}",
                sourceId, targetId, actor, e.getMessage() );
        return Optional.of( e.getMessage() != null ? e.getMessage() : "Internal error" );
    }
}

@Override
public NodeResult tryUpsertNode( final String name, final String nodeType, final String sourcePage,
                                  final java.util.Map<String, Object> props, final String actor ) {
    try {
        final com.wikantik.api.knowledge.KgNode node = kg.upsertNode( name, nodeType, sourcePage,
                com.wikantik.api.knowledge.Provenance.HUMAN_AUTHORED,
                props == null ? java.util.Map.of() : props );
        if ( node == null ) {
            return NodeResult.fail( "node not visible after insert (excluded source page or other policy filter)" );
        }
        return NodeResult.ok( node.id() );
    } catch ( final RuntimeException e ) {
        LOG.warn( "tryUpsertNode: name={} actor={}: {}", name, actor, e.getMessage() );
        return NodeResult.fail( e.getMessage() != null ? e.getMessage() : "Internal error" );
    }
}
```

- [ ] **Step 5: Run, expect pass**

Run: `mvn -pl wikantik-main -Dtest=DefaultKgCurationOpsTest test`
Expected: all tests pass.

- [ ] **Step 6: Commit**

```bash
git add wikantik-api/src/main/java/com/wikantik/api/knowledge/KgCurationOps.java \
        wikantik-main/src/main/java/com/wikantik/knowledge/curation/DefaultKgCurationOps.java \
        wikantik-main/src/test/java/com/wikantik/knowledge/curation/DefaultKgCurationOpsTest.java
git commit -m "feat(kg-curation): node ops on facade (upsert/delete/merge)"
```

---

## Task 5: Expose `KgCurationOps` through `KnowledgeSubsystem` / bridge

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/knowledge/subsystem/KnowledgeSubsystem.java`
- Modify: `wikantik-main/src/main/java/com/wikantik/knowledge/subsystem/KnowledgeSubsystemFactory.java`
- Modify: `wikantik-main/src/main/java/com/wikantik/knowledge/subsystem/KnowledgeSubsystemBridge.java`

- [ ] **Step 1: Add `kgCurationOps` to the `Services` record**

In `KnowledgeSubsystem.java` (~line 138), extend the `Services` record:

```java
public record Services(
        KnowledgeGraphService kgService,
        // ... existing fields ...
        KgCurationOps kgCurationOps     // <-- new
) { }
```

- [ ] **Step 2: Build the facade in `KnowledgeSubsystemFactory`**

Where the factory currently constructs `kgService`, also construct `DefaultKgCurationOps` with the engine's `PageManager` + `PageSaveHelper`, and pass it into the `Services` record.

```java
final KgCurationOps curation = new DefaultKgCurationOps( kgService, pageManager,
        new PageSaveHelper( engine, pageManager ) );
return new KnowledgeSubsystem.Services( kgService, /*…existing args…*/, curation );
```

- [ ] **Step 3: Update the bridge fallback path**

In `KnowledgeSubsystemBridge.rebuildFromManagers(...)` and the early-return null path in `fromLegacyEngine(...)`, pass `null` for the new `kgCurationOps` field so the absent-knowledge path still compiles. Callers must handle `kgCurationOps() == null`.

- [ ] **Step 4: Run a compile pass over the reactor's affected modules**

Run: `mvn -pl wikantik-main,wikantik-rest,wikantik-admin-mcp -am compile -q`
Expected: BUILD SUCCESS. Any callers that destructure the `Services` record positionally need an updated constructor — fix in-flight before continuing.

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/knowledge/subsystem/*.java
git commit -m "feat(kg-curation): expose KgCurationOps via KnowledgeSubsystem bridge"
```

---

## Task 6: Extract `ProposalConflictFlags` helper

**Files:**
- Create: `wikantik-knowledge/src/main/java/com/wikantik/knowledge/curation/ProposalConflictFlags.java`
- Create: `wikantik-knowledge/src/test/java/com/wikantik/knowledge/curation/ProposalConflictFlagsTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.wikantik.knowledge.curation;

import com.wikantik.api.knowledge.KgNode;
import com.wikantik.api.knowledge.KgProposal;
import com.wikantik.api.knowledge.KnowledgeGraphService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

public class ProposalConflictFlagsTest {

    @Test
    void newNodeProposalSetsNodeExistsTrueWhenNameResolves() {
        final KnowledgeGraphService svc = Mockito.mock( KnowledgeGraphService.class );
        final KgProposal p = Mockito.mock( KgProposal.class );
        when( p.proposalType() ).thenReturn( "new-node" );
        when( p.proposedData() ).thenReturn( Map.of( "name", "Raft" ) );
        final KgNode existing = Mockito.mock( KgNode.class );
        when( existing.id() ).thenReturn( UUID.randomUUID() );
        when( svc.getNodeByName( "Raft" ) ).thenReturn( existing );

        final Map<String, Object> flags = ProposalConflictFlags.forProposal( svc, p );
        assertEquals( Boolean.TRUE, flags.get( "node_exists" ) );
        assertNotNull( flags.get( "existing_node_id" ) );
    }

    @Test
    void newEdgeProposalSetsPreviouslyRejectedFlagWhenIsRejectedReturnsTrue() {
        final KnowledgeGraphService svc = Mockito.mock( KnowledgeGraphService.class );
        final KgProposal p = Mockito.mock( KgProposal.class );
        when( p.proposalType() ).thenReturn( "new-edge" );
        when( p.proposedData() ).thenReturn( Map.of(
                "source", "A", "target", "B", "relationship", "depends_on" ) );
        when( svc.isRejected( "A", "B", "depends_on" ) ).thenReturn( true );

        final Map<String, Object> flags = ProposalConflictFlags.forProposal( svc, p );
        assertEquals( Boolean.TRUE, flags.get( "edge_previously_rejected" ) );
    }

    @Test
    void unrelatedFlagsAreOmittedNotNull() {
        final KnowledgeGraphService svc = Mockito.mock( KnowledgeGraphService.class );
        final KgProposal p = Mockito.mock( KgProposal.class );
        when( p.proposalType() ).thenReturn( "new-node" );
        when( p.proposedData() ).thenReturn( Map.of( "name", "Raft" ) );
        when( svc.getNodeByName( "Raft" ) ).thenReturn( null );

        final Map<String, Object> flags = ProposalConflictFlags.forProposal( svc, p );
        assertFalse( flags.containsKey( "edge_previously_rejected" ) );
    }
}
```

- [ ] **Step 2: Run, expect compile fail**

Run: `mvn -pl wikantik-knowledge -Dtest=ProposalConflictFlagsTest test-compile`
Expected: class not found.

- [ ] **Step 3: Implement the helper**

```java
package com.wikantik.knowledge.curation;

import com.wikantik.api.knowledge.KgNode;
import com.wikantik.api.knowledge.KgProposal;
import com.wikantik.api.knowledge.KnowledgeGraphService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.LinkedHashMap;
import java.util.Map;

public final class ProposalConflictFlags {

    private static final Logger LOG = LogManager.getLogger( ProposalConflictFlags.class );

    private ProposalConflictFlags() { }

    public static Map< String, Object > forProposal( final KnowledgeGraphService svc, final KgProposal p ) {
        final Map< String, Object > flags = new LinkedHashMap<>();
        if ( p == null || p.proposedData() == null ) return flags;
        try {
            if ( "new-node".equals( p.proposalType() ) ) {
                final Object name = p.proposedData().get( "name" );
                if ( name instanceof String s ) {
                    final KgNode existing = svc.getNodeByName( s );
                    flags.put( "node_exists", existing != null );
                    if ( existing != null ) flags.put( "existing_node_id", existing.id().toString() );
                }
            } else if ( "new-edge".equals( p.proposalType() ) ) {
                final Object src = p.proposedData().get( "source" );
                final Object tgt = p.proposedData().get( "target" );
                final Object rel = p.proposedData().get( "relationship" );
                if ( src instanceof String s && tgt instanceof String t && rel instanceof String r ) {
                    flags.put( "edge_previously_rejected", svc.isRejected( s, t, r ) );
                }
            }
        } catch ( final Exception e ) {
            LOG.warn( "Failed to compute conflict flags for proposal {}: {}", p.id(), e.getMessage() );
        }
        return flags;
    }
}
```

- [ ] **Step 4: Run, expect pass**

Run: `mvn -pl wikantik-knowledge -Dtest=ProposalConflictFlagsTest test`
Expected: 3 tests pass.

- [ ] **Step 5: Commit**

```bash
git add wikantik-knowledge/src/main/java/com/wikantik/knowledge/curation/ProposalConflictFlags.java \
        wikantik-knowledge/src/test/java/com/wikantik/knowledge/curation/ProposalConflictFlagsTest.java
git commit -m "feat(kg-curation): shared ProposalConflictFlags helper"
```

---

## Task 7: Refactor `AdminKnowledgeResource` to use the facade + helper

**Files:**
- Modify: `wikantik-rest/src/main/java/com/wikantik/rest/AdminKnowledgeResource.java`
- Run: existing tests in `wikantik-rest`

- [ ] **Step 1: Add facade lookup**

Add a private accessor that pulls `KgCurationOps` from the engine via the same bridge already used to pull `KnowledgeGraphService`. Cache the result in a field if/when one is added on the resource for a request; otherwise re-resolve per request (matches current pattern).

- [ ] **Step 2: Replace `tryApproveProposal` / `tryRejectProposal` / `tryJudgeProposal`**

Delete the three private methods (lines ~636-687) and update the two call sites (~485, ~606-608) to call `kgCurationOps().tryApproveProposal(id, actor)` etc. The single-id `approve`/`reject` handlers also dispatch through the facade now — same envelope, less code.

- [ ] **Step 3: Replace `writeFrontmatterIfEdge`**

Delete the private method (lines ~1006-1054) and the two call sites (~486, ~644). The facade now owns this side effect.

- [ ] **Step 4: Replace inline conflict-flag computation with the helper**

At `AdminKnowledgeResource.java:1224-1240`, drop the inline branching and call:

```java
final java.util.Map< String, Object > flags = ProposalConflictFlags.forProposal( service, p );
map.putAll( flags );
```

- [ ] **Step 5: Route edge upsert/confirm/delete/delete-and-reject and node upsert/delete/merge through the facade**

In each `handlePostEdge*` and `handlePostNode*` method, replace the direct `service.upsertEdge(...)` / `service.confirmEdge(...)` / etc. calls with the corresponding `kgCurationOps().try*(...)` variants. Error responses use `sendError(400)` for the per-op error message; success paths still return the existing JSON shapes (callers extract `result.edgeId()` / `result.nodeId()`).

- [ ] **Step 6: Run the affected REST tests**

Run: `mvn -pl wikantik-rest test`
Expected: all REST tests still pass (suite includes `AdminKnowledgeResourceTest`, `AdminKnowledgeResourceMockTest`, `AdminKnowledgeResourceJudgeTest`, `AdminKnowledgeResourceBulkTest`, `AdminKnowledgeResourceEdgeCurationTest`). Any test asserting on a specific log line moved out of the resource needs its expectation updated to the facade's log line; do that inline if it fails.

- [ ] **Step 7: Commit**

```bash
git add wikantik-rest/src/main/java/com/wikantik/rest/AdminKnowledgeResource.java
git commit -m "refactor(kg-curation): route REST through KgCurationOps + ProposalConflictFlags"
```

---

## Task 8: Enrich `ListProposalsTool` with conflict flags

**Files:**
- Modify: `wikantik-admin-mcp/src/main/java/com/wikantik/mcp/tools/ListProposalsTool.java`
- Modify: `wikantik-admin-mcp/src/test/java/com/wikantik/mcp/tools/ListProposalsToolTest.java`

- [ ] **Step 1: Add a failing test**

In `ListProposalsToolTest`, add:

```java
@Test
void executeAddsNodeExistsFlagForNewNodeProposal() {
    final KnowledgeGraphService svc = Mockito.mock( KnowledgeGraphService.class );
    final KgProposal p = Mockito.mock( KgProposal.class );
    when( p.id() ).thenReturn( UUID.randomUUID() );
    when( p.proposalType() ).thenReturn( "new-node" );
    when( p.proposedData() ).thenReturn( java.util.Map.of( "name", "Raft" ) );
    when( p.status() ).thenReturn( "pending" );
    when( svc.listProposals( "pending", null, 50, 0 ) ).thenReturn( java.util.List.of( p ) );
    when( svc.getNodeByName( "Raft" ) ).thenReturn( Mockito.mock( com.wikantik.api.knowledge.KgNode.class ) );

    final var result = new ListProposalsTool( svc ).execute( java.util.Map.of( "status", "pending" ) );
    final String body = ( ( io.modelcontextprotocol.spec.McpSchema.TextContent )
            result.content().get( 0 ) ).text();
    org.junit.jupiter.api.Assertions.assertTrue( body.contains( "\"node_exists\":true" ),
            "Expected node_exists flag in payload: " + body );
}
```

- [ ] **Step 2: Run, expect fail**

Run: `mvn -pl wikantik-admin-mcp -Dtest=ListProposalsToolTest test`
Expected: test fails because output does not contain `node_exists`.

- [ ] **Step 3: Update the tool**

In `ListProposalsTool.execute(...)`, where the `Map<String, Object> map` is being built for each proposal, append:

```java
map.putAll( com.wikantik.knowledge.curation.ProposalConflictFlags.forProposal( service, p ) );
```

…and update the `outputSchema.examples` entry (line ~76-96) so the canonical example includes `"node_exists": false` and `"edge_previously_rejected": true` on the example edge proposal.

- [ ] **Step 4: Run, expect pass**

Run: `mvn -pl wikantik-admin-mcp -Dtest=ListProposalsToolTest test`
Expected: all tests pass.

- [ ] **Step 5: Commit**

```bash
git add wikantik-admin-mcp/src/main/java/com/wikantik/mcp/tools/ListProposalsTool.java \
        wikantik-admin-mcp/src/test/java/com/wikantik/mcp/tools/ListProposalsToolTest.java
git commit -m "feat(mcp): enrich list_proposals with node_exists / edge_previously_rejected"
```

---

## Task 9: Add `McpAudit.logBulkWrite` and `McpConfig.kgCurationBulkLimit()`

**Files:**
- Modify: `wikantik-admin-mcp/src/main/java/com/wikantik/mcp/tools/McpAudit.java`
- Modify: `wikantik-admin-mcp/src/main/java/com/wikantik/mcp/McpConfig.java`
- Create: `wikantik-admin-mcp/src/test/java/com/wikantik/mcp/McpConfigBulkLimitTest.java`

- [ ] **Step 1: Failing test for the config accessor**

```java
package com.wikantik.mcp;

import org.junit.jupiter.api.Test;
import java.util.Properties;
import static org.junit.jupiter.api.Assertions.*;

public class McpConfigBulkLimitTest {

    @Test
    void defaultBulkLimitIs50() {
        assertEquals( 50, new McpConfig( new Properties() ).kgCurationBulkLimit() );
    }

    @Test
    void zeroOrNegativeFallsBackToDefault() {
        final Properties p = new Properties();
        p.setProperty( "wikantik.mcp.kg_curation.bulk_limit", "0" );
        assertEquals( 50, new McpConfig( p ).kgCurationBulkLimit() );
    }

    @Test
    void positiveValueIsHonoured() {
        final Properties p = new Properties();
        p.setProperty( "wikantik.mcp.kg_curation.bulk_limit", "12" );
        assertEquals( 12, new McpConfig( p ).kgCurationBulkLimit() );
    }
}
```

- [ ] **Step 2: Run, expect fail**

Run: `mvn -pl wikantik-admin-mcp -Dtest=McpConfigBulkLimitTest test-compile`
Expected: compile error — method missing. (Also confirm `McpConfig` has a `Properties`-arg ctor — if not, add an overload that takes one; the existing zero-arg ctor delegates.)

- [ ] **Step 3: Add the accessor**

```java
// in McpConfig
private static final int DEFAULT_KG_BULK_LIMIT = 50;

public int kgCurationBulkLimit() {
    final String raw = props.getProperty( "wikantik.mcp.kg_curation.bulk_limit" );
    if ( raw == null || raw.isBlank() ) return DEFAULT_KG_BULK_LIMIT;
    try {
        final int v = Integer.parseInt( raw.trim() );
        if ( v <= 0 ) {
            LOG.warn( "wikantik.mcp.kg_curation.bulk_limit={} is not positive — falling back to default {}",
                    raw, DEFAULT_KG_BULK_LIMIT );
            return DEFAULT_KG_BULK_LIMIT;
        }
        return v;
    } catch ( final NumberFormatException e ) {
        LOG.warn( "wikantik.mcp.kg_curation.bulk_limit={} is not an integer — falling back to default {}",
                raw, DEFAULT_KG_BULK_LIMIT );
        return DEFAULT_KG_BULK_LIMIT;
    }
}
```

- [ ] **Step 4: Add a bulk-write audit helper**

```java
// in McpAudit
public static void logBulkWrite( final String tool, final int attempted, final int succeeded,
                                  final int failed, final String author ) {
    LOG.info( "tool={} action=bulk attempted={} succeeded={} failed={} author={}",
            tool, attempted, succeeded, failed, author );
}
```

- [ ] **Step 5: Run, expect pass**

Run: `mvn -pl wikantik-admin-mcp -Dtest=McpConfigBulkLimitTest test`
Expected: 3 tests pass.

- [ ] **Step 6: Commit**

```bash
git add wikantik-admin-mcp/src/main/java/com/wikantik/mcp/tools/McpAudit.java \
        wikantik-admin-mcp/src/main/java/com/wikantik/mcp/McpConfig.java \
        wikantik-admin-mcp/src/test/java/com/wikantik/mcp/McpConfigBulkLimitTest.java
git commit -m "feat(mcp): bulk-limit config (default 50) + logBulkWrite audit"
```

---

## Task 10: Create `InspectProposalsTool`

**Files:**
- Create: `wikantik-admin-mcp/src/main/java/com/wikantik/mcp/tools/InspectProposalsTool.java`
- Create: `wikantik-admin-mcp/src/test/java/com/wikantik/mcp/tools/InspectProposalsToolTest.java`

- [ ] **Step 1: Write failing tests**

```java
package com.wikantik.mcp.tools;

import com.wikantik.api.knowledge.KgProposal;
import com.wikantik.api.knowledge.KgProposalReview;
import com.wikantik.api.knowledge.KnowledgeGraphService;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

public class InspectProposalsToolTest {

    private KnowledgeGraphService svc;
    private InspectProposalsTool tool;

    @BeforeEach void setUp() {
        svc = Mockito.mock( KnowledgeGraphService.class );
        tool = new InspectProposalsTool( svc, 50 );
    }

    @Test
    void capExceededReturnsTopLevelError() {
        final List< String > ids = new java.util.ArrayList<>();
        for ( int i = 0; i < 51; i++ ) ids.add( UUID.randomUUID().toString() );
        final McpSchema.CallToolResult r = tool.execute( Map.of( "ids", ids ) );
        assertTrue( r.isError() );
    }

    @Test
    void unknownIdLandsInMissingArray() {
        final UUID id = UUID.randomUUID();
        when( svc.getProposal( id ) ).thenReturn( null );
        final McpSchema.CallToolResult r = tool.execute( Map.of( "ids", List.of( id.toString() ) ) );
        assertFalse( r.isError() );
        final String body = ( ( McpSchema.TextContent ) r.content().get( 0 ) ).text();
        assertTrue( body.contains( "\"missing\":[\"" + id + "\"]" ),
                "Expected unknown id in missing[]: " + body );
    }

    @Test
    void resolvedProposalIncludesConflictsAndPriorReviews() {
        final UUID id = UUID.randomUUID();
        final KgProposal p = Mockito.mock( KgProposal.class );
        when( p.id() ).thenReturn( id );
        when( p.proposalType() ).thenReturn( "new-node" );
        when( p.proposedData() ).thenReturn( Map.of( "name", "Raft" ) );
        when( svc.getProposal( id ) ).thenReturn( p );
        when( svc.listReviews( id ) ).thenReturn( List.of() );
        when( svc.getNodeByName( "Raft" ) ).thenReturn( null );

        final McpSchema.CallToolResult r = tool.execute( Map.of( "ids", List.of( id.toString() ) ) );
        final String body = ( ( McpSchema.TextContent ) r.content().get( 0 ) ).text();
        assertTrue( body.contains( "\"node_exists\":false" ), body );
        assertTrue( body.contains( "\"prior_reviews\":[]" ), body );
    }

    @Test
    void invalidUuidLandsInMissingArrayNotTopLevel() {
        final McpSchema.CallToolResult r = tool.execute( Map.of( "ids", List.of( "not-a-uuid" ) ) );
        assertFalse( r.isError() );
        final String body = ( ( McpSchema.TextContent ) r.content().get( 0 ) ).text();
        assertTrue( body.contains( "\"missing\":[\"not-a-uuid\"]" ), body );
    }
}
```

Note: `KnowledgeGraphService` already exposes `getProposal( UUID )` and `listReviews( UUID )`. Re-check the interface; if `getProposal` doesn't exist, either add it (preferred) or compose the read from `listProposals(...)` filtered to a single id. Quick scan: `KnowledgeGraphService.java:186` declares `listReviews`; verify a `getProposal(UUID)` exists or add `KgProposal getProposal(UUID)` to the interface as part of this task with a one-line `DefaultKnowledgeGraphService` impl that delegates to the existing repo query.

- [ ] **Step 2: Run, expect compile fail**

Run: `mvn -pl wikantik-admin-mcp -Dtest=InspectProposalsToolTest test-compile`
Expected: class not found.

- [ ] **Step 3: Implement the tool**

```java
package com.wikantik.mcp.tools;

import com.wikantik.api.knowledge.KgProposal;
import com.wikantik.api.knowledge.KgProposalReview;
import com.wikantik.api.knowledge.KnowledgeGraphService;
import com.wikantik.knowledge.curation.ProposalConflictFlags;
import io.modelcontextprotocol.spec.McpSchema;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.*;

public class InspectProposalsTool implements McpTool {

    private static final Logger LOG = LogManager.getLogger( InspectProposalsTool.class );
    public static final String TOOL_NAME = "inspect_proposals";

    private final KnowledgeGraphService service;
    private final int bulkLimit;

    public InspectProposalsTool( final KnowledgeGraphService service, final int bulkLimit ) {
        this.service = service;
        this.bulkLimit = bulkLimit;
    }

    @Override public String name() { return TOOL_NAME; }

    @Override public McpSchema.Tool definition() {
        final Map< String, Object > properties = new LinkedHashMap<>();
        properties.put( "ids", Map.of(
                "type", "array",
                "items", Map.of( "type", "string" ),
                "description", "Proposal UUIDs to inspect (1.." + bulkLimit + ")",
                "examples", List.of( List.of( "8f3c2a1b-7e4d-4f5a-9b6c-1d2e3f4a5b6c" ) )
        ) );

        final Map< String, Object > exampleOut = new LinkedHashMap<>();
        exampleOut.put( "proposals", List.of( Map.of(
                "id", "8f3c2a1b-...",
                "proposal", Map.of( "proposal_type", "new-edge", "status", "pending" ),
                "conflicts", Map.of( "edge_previously_rejected", true ),
                "prior_reviews", List.of(),
                "linked_entity", Map.of() ) ) );
        exampleOut.put( "missing", List.of() );

        final Map< String, Object > outputSchema = new LinkedHashMap<>();
        outputSchema.put( "type", "object" );
        outputSchema.put( "examples", List.of( exampleOut ) );

        return McpSchema.Tool.builder()
                .name( TOOL_NAME )
                .description( "Bulk deep-dive read of 1.." + bulkLimit + " proposals. " +
                        "Returns full proposal, conflict flags, prior reviews, and any linked entity snapshot. " +
                        "Unknown ids land in `missing[]`." )
                .inputSchema( new McpSchema.JsonSchema( "object", properties, List.of( "ids" ), null, null, null ) )
                .outputSchema( outputSchema )
                .annotations( new McpSchema.ToolAnnotations( null, true, false, true, null, null ) )
                .build();
    }

    @Override public McpSchema.CallToolResult execute( final Map< String, Object > arguments ) {
        final Object raw = arguments.get( "ids" );
        if ( !( raw instanceof List< ? > rawList ) || rawList.isEmpty() ) {
            return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON,
                    "ids is required and must be a non-empty array" );
        }
        if ( rawList.size() > bulkLimit ) {
            return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON,
                    "bulk limit exceeded: " + rawList.size() + " > " + bulkLimit );
        }

        final List< Map< String, Object > > proposals = new ArrayList<>();
        final List< String > missing = new ArrayList<>();

        for ( final Object idEl : rawList ) {
            final String idStr = idEl == null ? null : idEl.toString();
            UUID id;
            try { id = UUID.fromString( idStr ); }
            catch ( final IllegalArgumentException e ) { missing.add( idStr ); continue; }

            final KgProposal p = service.getProposal( id );
            if ( p == null ) { missing.add( idStr ); continue; }

            final Map< String, Object > entry = new LinkedHashMap<>();
            entry.put( "id", id.toString() );
            entry.put( "proposal", proposalToMap( p ) );
            entry.put( "conflicts", ProposalConflictFlags.forProposal( service, p ) );
            entry.put( "prior_reviews", reviewsToMaps( service.listReviews( id ) ) );
            entry.put( "linked_entity", linkedEntity( p ) );
            proposals.add( entry );
        }

        final Map< String, Object > out = new LinkedHashMap<>();
        out.put( "proposals", proposals );
        out.put( "missing", missing );
        return McpToolUtils.jsonResult( McpToolUtils.SHARED_GSON, out );
    }

    private Map< String, Object > proposalToMap( final KgProposal p ) {
        final Map< String, Object > m = new LinkedHashMap<>();
        m.put( "proposal_type", p.proposalType() );
        m.put( "source_page", p.sourcePage() );
        m.put( "proposed_data", p.proposedData() );
        m.put( "confidence", p.confidence() );
        m.put( "reasoning", p.reasoning() );
        m.put( "status", p.status() );
        m.put( "reviewed_by", p.reviewedBy() );
        m.put( "created", p.created() != null ? p.created().toString() : null );
        m.put( "reviewed_at", p.reviewedAt() != null ? p.reviewedAt().toString() : null );
        return m;
    }

    private List< Map< String, Object > > reviewsToMaps( final List< KgProposalReview > reviews ) {
        if ( reviews == null ) return List.of();
        final List< Map< String, Object > > out = new ArrayList<>( reviews.size() );
        for ( final KgProposalReview r : reviews ) {
            final Map< String, Object > m = new LinkedHashMap<>();
            m.put( "reviewer", r.reviewer() );
            m.put( "verdict", r.verdict() );
            m.put( "reason", r.reason() );
            m.put( "at", r.at() != null ? r.at().toString() : null );
            out.add( m );
        }
        return out;
    }

    private Map< String, Object > linkedEntity( final KgProposal p ) {
        // For new-node, look up the existing node-by-name; for new-edge, no canonical link.
        if ( "new-node".equals( p.proposalType() ) && p.proposedData() != null ) {
            final Object name = p.proposedData().get( "name" );
            if ( name instanceof String s ) {
                final com.wikantik.api.knowledge.KgNode existing = service.getNodeByName( s );
                if ( existing != null ) {
                    return Map.of( "kind", "node",
                            "id", existing.id().toString(),
                            "name", existing.name(),
                            "type", existing.nodeType() );
                }
            }
        }
        return Map.of();
    }
}
```

If the iterator field accessors on `KgProposalReview` differ from the names used above, adjust to the record/class's actual accessors. Treat undefined accessors as a compile failure to fix.

- [ ] **Step 4: Run, expect pass**

Run: `mvn -pl wikantik-admin-mcp -Dtest=InspectProposalsToolTest test`
Expected: 4 tests pass.

- [ ] **Step 5: Commit**

```bash
git add wikantik-admin-mcp/src/main/java/com/wikantik/mcp/tools/InspectProposalsTool.java \
        wikantik-admin-mcp/src/test/java/com/wikantik/mcp/tools/InspectProposalsToolTest.java
git commit -m "feat(mcp): inspect_proposals deep-dive bulk read tool"
```

---

## Task 11: Create `ReviewProposalsTool`

**Files:**
- Create: `wikantik-admin-mcp/src/main/java/com/wikantik/mcp/tools/ReviewProposalsTool.java`
- Create: `wikantik-admin-mcp/src/test/java/com/wikantik/mcp/tools/ReviewProposalsToolTest.java`

- [ ] **Step 1: Failing tests**

```java
package com.wikantik.mcp.tools;

import com.wikantik.api.knowledge.KgCurationOps;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

public class ReviewProposalsToolTest {

    private KgCurationOps ops;
    private ReviewProposalsTool tool;

    @BeforeEach void setUp() {
        ops = Mockito.mock( KgCurationOps.class );
        tool = new ReviewProposalsTool( ops, 50 );
        tool.setDefaultAuthor( "alice" );
    }

    @Test
    void approvesAllSucceedsReturnsEnvelope() {
        final UUID a = UUID.randomUUID();
        final UUID b = UUID.randomUUID();
        when( ops.tryApproveProposal( eq( a ), eq( "alice" ) ) ).thenReturn( Optional.empty() );
        when( ops.tryApproveProposal( eq( b ), eq( "alice" ) ) ).thenReturn( Optional.empty() );

        final McpSchema.CallToolResult r = tool.execute( Map.of(
                "verdict", "approve",
                "ids", List.of( a.toString(), b.toString() ) ) );
        final String body = ( ( McpSchema.TextContent ) r.content().get( 0 ) ).text();
        assertTrue( body.contains( "\"succeeded\":[\"" + a + "\",\"" + b + "\"]" )
                || body.contains( "\"succeeded\":[\"" + b + "\",\"" + a + "\"]" ), body );
        assertTrue( body.contains( "\"failed\":[]" ), body );
    }

    @Test
    void mixedSuccessAndFailureKeepsPerIdErrors() {
        final UUID ok = UUID.randomUUID();
        final UUID bad = UUID.randomUUID();
        when( ops.tryApproveProposal( eq( ok ), any() ) ).thenReturn( Optional.empty() );
        when( ops.tryApproveProposal( eq( bad ), any() ) )
                .thenReturn( Optional.of( "Not found: " + bad ) );

        final McpSchema.CallToolResult r = tool.execute( Map.of(
                "verdict", "approve",
                "ids", List.of( ok.toString(), bad.toString() ) ) );
        final String body = ( ( McpSchema.TextContent ) r.content().get( 0 ) ).text();
        assertTrue( body.contains( "\"id\":\"" + bad + "\"" ), body );
        assertTrue( body.contains( "Not found" ), body );
    }

    @Test
    void rejectWithoutReasonIsTopLevelError() {
        final McpSchema.CallToolResult r = tool.execute( Map.of(
                "verdict", "reject",
                "ids", List.of( UUID.randomUUID().toString() ) ) );
        assertTrue( r.isError() );
    }

    @Test
    void bulkLimitExceededIsTopLevelError() {
        final List< String > ids = new java.util.ArrayList<>();
        for ( int i = 0; i < 51; i++ ) ids.add( UUID.randomUUID().toString() );
        final McpSchema.CallToolResult r = tool.execute( Map.of(
                "verdict", "approve", "ids", ids ) );
        assertTrue( r.isError() );
    }

    @Test
    void invalidUuidYieldsPerIdFailure() {
        final McpSchema.CallToolResult r = tool.execute( Map.of(
                "verdict", "approve", "ids", List.of( "not-a-uuid" ) ) );
        final String body = ( ( McpSchema.TextContent ) r.content().get( 0 ) ).text();
        assertTrue( body.contains( "Invalid UUID" ), body );
    }
}
```

- [ ] **Step 2: Run, expect compile fail**

Run: `mvn -pl wikantik-admin-mcp -Dtest=ReviewProposalsToolTest test-compile`
Expected: class not found.

- [ ] **Step 3: Implement the tool**

```java
package com.wikantik.mcp.tools;

import com.wikantik.api.knowledge.KgCurationOps;
import io.modelcontextprotocol.spec.McpSchema;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.*;

public class ReviewProposalsTool implements McpTool, AuthorConfigurable {

    private static final Logger LOG = LogManager.getLogger( ReviewProposalsTool.class );
    public static final String TOOL_NAME = "review_proposals";

    private final KgCurationOps ops;
    private final int bulkLimit;
    private volatile String defaultAuthor = "admin";

    public ReviewProposalsTool( final KgCurationOps ops, final int bulkLimit ) {
        this.ops = ops;
        this.bulkLimit = bulkLimit;
    }

    @Override public String name() { return TOOL_NAME; }
    @Override public void setDefaultAuthor( final String author ) { this.defaultAuthor = author; }

    @Override public McpSchema.Tool definition() {
        final Map< String, Object > properties = new LinkedHashMap<>();
        properties.put( "verdict", Map.of(
                "type", "string",
                "enum", List.of( "approve", "reject", "judge" ),
                "description", "Bulk verdict applied to every id.",
                "examples", List.of( "approve" )
        ) );
        properties.put( "ids", Map.of(
                "type", "array",
                "items", Map.of( "type", "string" ),
                "description", "Proposal UUIDs (1.." + bulkLimit + ")"
        ) );
        properties.put( "reason", Map.of(
                "type", "string",
                "description", "Required iff verdict == reject"
        ) );

        final Map< String, Object > exampleApprove = Map.of(
                "status", "completed",
                "succeeded", List.of( "8f3c2a1b-..." ),
                "failed", List.of(),
                "message", "1 of 1 proposals approved" );
        final Map< String, Object > exampleReject = Map.of(
                "status", "completed",
                "succeeded", List.of(),
                "failed", List.of( Map.of( "id", "8f3c2a1b-...", "error", "Not found: ..." ) ),
                "message", "0 of 1 proposals rejected" );

        final Map< String, Object > outputSchema = new LinkedHashMap<>();
        outputSchema.put( "type", "object" );
        outputSchema.put( "examples", List.of( exampleApprove, exampleReject ) );

        return McpSchema.Tool.builder()
                .name( TOOL_NAME )
                .description( "Bulk review of 1.." + bulkLimit + " KG proposals. Verdict is applied " +
                        "to every id; per-id failures surface in `failed[]` with a reason. " +
                        "verdict='reject' requires a top-level `reason`." )
                .inputSchema( new McpSchema.JsonSchema( "object", properties,
                        List.of( "verdict", "ids" ), null, null, null ) )
                .outputSchema( outputSchema )
                .annotations( new McpSchema.ToolAnnotations( null, false, true, true, null, null ) )
                .build();
    }

    @Override public McpSchema.CallToolResult execute( final Map< String, Object > args ) {
        final String verdict = McpToolUtils.getString( args, "verdict" );
        if ( verdict == null || verdict.isBlank() ) {
            return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON,
                    "verdict is required (approve | reject | judge)" );
        }
        if ( !Set.of( "approve", "reject", "judge" ).contains( verdict ) ) {
            return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON,
                    "Unsupported verdict '" + verdict + "'" );
        }
        final String reason;
        if ( "reject".equals( verdict ) ) {
            reason = McpToolUtils.getString( args, "reason" );
            if ( reason == null || reason.isBlank() ) {
                return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON,
                        "reason is required for verdict='reject'" );
            }
        } else { reason = null; }

        final Object rawIds = args.get( "ids" );
        if ( !( rawIds instanceof List< ? > rawList ) || rawList.isEmpty() ) {
            return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON,
                    "ids is required and must be a non-empty array" );
        }
        if ( rawList.size() > bulkLimit ) {
            return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON,
                    "bulk limit exceeded: " + rawList.size() + " > " + bulkLimit );
        }

        final List< String > succeeded = new ArrayList<>();
        final List< Map< String, Object > > failed = new ArrayList<>();

        for ( final Object idEl : rawList ) {
            final String idStr = idEl == null ? null : idEl.toString();
            UUID id;
            try { id = UUID.fromString( idStr ); }
            catch ( final IllegalArgumentException e ) {
                final Map< String, Object > f = new LinkedHashMap<>();
                f.put( "id", idStr );
                f.put( "error", "Invalid UUID: " + idStr );
                failed.add( f );
                continue;
            }

            final Optional< String > err = switch ( verdict ) {
                case "approve" -> ops.tryApproveProposal( id, defaultAuthor );
                case "reject"  -> ops.tryRejectProposal( id, defaultAuthor, reason );
                default        -> ops.tryJudgeProposal( id, defaultAuthor );
            };
            if ( err.isEmpty() ) succeeded.add( idStr );
            else {
                final Map< String, Object > f = new LinkedHashMap<>();
                f.put( "id", idStr );
                f.put( "error", err.get() );
                failed.add( f );
            }
        }

        McpAudit.logBulkWrite( TOOL_NAME, rawList.size(), succeeded.size(), failed.size(), defaultAuthor );

        final Map< String, Object > out = new LinkedHashMap<>();
        out.put( "status", "completed" );
        out.put( "succeeded", succeeded );
        out.put( "failed", failed );
        out.put( "message", succeeded.size() + " of " + rawList.size() + " proposals " + verdict + "d" );
        return McpToolUtils.jsonResult( McpToolUtils.SHARED_GSON, out );
    }
}
```

- [ ] **Step 4: Run, expect pass**

Run: `mvn -pl wikantik-admin-mcp -Dtest=ReviewProposalsToolTest test`
Expected: 5 tests pass.

- [ ] **Step 5: Commit**

```bash
git add wikantik-admin-mcp/src/main/java/com/wikantik/mcp/tools/ReviewProposalsTool.java \
        wikantik-admin-mcp/src/test/java/com/wikantik/mcp/tools/ReviewProposalsToolTest.java
git commit -m "feat(mcp): review_proposals bulk approve/reject/judge tool"
```

---

## Task 12: Create `CurateEdgesTool`

**Files:**
- Create: `wikantik-admin-mcp/src/main/java/com/wikantik/mcp/tools/CurateEdgesTool.java`
- Create: `wikantik-admin-mcp/src/test/java/com/wikantik/mcp/tools/CurateEdgesToolTest.java`

- [ ] **Step 1: Failing tests**

```java
package com.wikantik.mcp.tools;

import com.wikantik.api.knowledge.KgCurationOps;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

public class CurateEdgesToolTest {

    private KgCurationOps ops;
    private CurateEdgesTool tool;

    @BeforeEach void setUp() {
        ops = Mockito.mock( KgCurationOps.class );
        tool = new CurateEdgesTool( ops, 50 );
        tool.setDefaultAuthor( "alice" );
    }

    @Test
    void upsertOpEchoesTagAndResultingEdgeId() {
        final UUID src = UUID.randomUUID();
        final UUID tgt = UUID.randomUUID();
        final UUID edgeId = UUID.randomUUID();
        when( ops.tryUpsertEdge( eq( src ), eq( tgt ), eq( "rel" ), any(), eq( "alice" ) ) )
                .thenReturn( KgCurationOps.EdgeResult.ok( edgeId ) );

        final McpSchema.CallToolResult r = tool.execute( Map.of(
                "operations", List.of( Map.of(
                        "action", "upsert",
                        "tag", "edge-1",
                        "source_id", src.toString(),
                        "target_id", tgt.toString(),
                        "relationship_type", "rel" ) ) ) );
        final String body = ( ( McpSchema.TextContent ) r.content().get( 0 ) ).text();
        assertTrue( body.contains( "\"tag\":\"edge-1\"" ), body );
        assertTrue( body.contains( "\"id\":\"" + edgeId + "\"" ), body );
    }

    @Test
    void confirmOpReportsServiceError() {
        final UUID id = UUID.randomUUID();
        when( ops.tryConfirmEdge( eq( id ), any() ) )
                .thenReturn( Optional.of( "Edge not found: " + id ) );

        final McpSchema.CallToolResult r = tool.execute( Map.of(
                "operations", List.of( Map.of(
                        "action", "confirm", "tag", "edge-2", "id", id.toString() ) ) ) );
        final String body = ( ( McpSchema.TextContent ) r.content().get( 0 ) ).text();
        assertTrue( body.contains( "Edge not found" ), body );
        assertTrue( body.contains( "\"action\":\"confirm\"" ), body );
    }

    @Test
    void unknownActionIsPerOpError() {
        final McpSchema.CallToolResult r = tool.execute( Map.of(
                "operations", List.of( Map.of(
                        "action", "bogus", "tag", "x" ) ) ) );
        final String body = ( ( McpSchema.TextContent ) r.content().get( 0 ) ).text();
        assertTrue( body.contains( "Unsupported action" ), body );
    }

    @Test
    void deleteAndRejectRequiresReason() {
        final McpSchema.CallToolResult r = tool.execute( Map.of(
                "operations", List.of( Map.of(
                        "action", "delete_and_reject", "tag", "x", "id", UUID.randomUUID().toString() ) ) ) );
        final String body = ( ( McpSchema.TextContent ) r.content().get( 0 ) ).text();
        assertTrue( body.contains( "reason is required" ), body );
    }

    @Test
    void bulkLimitExceededIsTopLevelError() {
        final List< Object > ops51 = new java.util.ArrayList<>();
        for ( int i = 0; i < 51; i++ ) ops51.add( Map.of( "action", "confirm",
                "id", UUID.randomUUID().toString() ) );
        final McpSchema.CallToolResult r = tool.execute( Map.of( "operations", ops51 ) );
        assertTrue( r.isError() );
    }
}
```

- [ ] **Step 2: Run, expect compile fail**

Run: `mvn -pl wikantik-admin-mcp -Dtest=CurateEdgesToolTest test-compile`

- [ ] **Step 3: Implement the tool**

```java
package com.wikantik.mcp.tools;

import com.wikantik.api.knowledge.KgCurationOps;
import io.modelcontextprotocol.spec.McpSchema;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.*;

public class CurateEdgesTool implements McpTool, AuthorConfigurable {

    private static final Logger LOG = LogManager.getLogger( CurateEdgesTool.class );
    public static final String TOOL_NAME = "curate_edges";

    private final KgCurationOps ops;
    private final int bulkLimit;
    private volatile String defaultAuthor = "admin";

    public CurateEdgesTool( final KgCurationOps ops, final int bulkLimit ) {
        this.ops = ops;
        this.bulkLimit = bulkLimit;
    }

    @Override public String name() { return TOOL_NAME; }
    @Override public void setDefaultAuthor( final String author ) { this.defaultAuthor = author; }

    @Override public McpSchema.Tool definition() {
        final Map< String, Object > properties = new LinkedHashMap<>();
        properties.put( "operations", Map.of(
                "type", "array",
                "description", "1.." + bulkLimit + " heterogeneous edge ops. Each item has `action` " +
                        "(upsert|confirm|delete|delete_and_reject) plus action-specific fields and an optional `tag`.",
                "items", Map.of( "type", "object" )
        ) );

        final Map< String, Object > exampleOut = Map.of(
                "status", "completed",
                "succeeded", List.of( Map.of( "tag", "edge-1", "action", "upsert",
                        "id", "8f3c2a1b-..." ) ),
                "failed", List.of(),
                "message", "1 of 1 edge operations applied" );
        final Map< String, Object > outputSchema = new LinkedHashMap<>();
        outputSchema.put( "type", "object" );
        outputSchema.put( "examples", List.of( exampleOut ) );

        return McpSchema.Tool.builder()
                .name( TOOL_NAME )
                .description( "Bulk heterogeneous edge curation. Actions: " +
                        "`upsert` (HUMAN_CURATED), `confirm` (elevate to human-curated), `delete`, " +
                        "`delete_and_reject` (delete + write rejection record)." )
                .inputSchema( new McpSchema.JsonSchema( "object", properties,
                        List.of( "operations" ), null, null, null ) )
                .outputSchema( outputSchema )
                .annotations( new McpSchema.ToolAnnotations( null, false, true, true, null, null ) )
                .build();
    }

    @Override public McpSchema.CallToolResult execute( final Map< String, Object > args ) {
        final Object raw = args.get( "operations" );
        if ( !( raw instanceof List< ? > rawList ) || rawList.isEmpty() ) {
            return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON,
                    "operations is required and must be a non-empty array" );
        }
        if ( rawList.size() > bulkLimit ) {
            return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON,
                    "bulk limit exceeded: " + rawList.size() + " > " + bulkLimit );
        }

        final List< Map< String, Object > > succeeded = new ArrayList<>();
        final List< Map< String, Object > > failed = new ArrayList<>();

        for ( final Object opEl : rawList ) {
            if ( !( opEl instanceof Map< ?, ? > opMap ) ) {
                failed.add( Map.of( "error", "operation must be an object" ) );
                continue;
            }
            final Map< String, Object > op = castStringKey( opMap );
            final String tag = stringOrNull( op.get( "tag" ) );
            final String action = stringOrNull( op.get( "action" ) );

            final Map< String, Object > result;
            switch ( action == null ? "" : action ) {
                case "upsert"            -> result = doUpsert( op );
                case "confirm"           -> result = doConfirm( op );
                case "delete"            -> result = doDelete( op );
                case "delete_and_reject" -> result = doDeleteAndReject( op );
                default                  -> result = Map.of( "error",
                        "Unsupported action '" + action + "' — supported: upsert, confirm, delete, delete_and_reject" );
            }

            final Map< String, Object > entry = new LinkedHashMap<>();
            entry.put( "tag", tag );
            entry.put( "action", action );
            entry.putAll( result );
            if ( entry.containsKey( "error" ) ) failed.add( entry );
            else succeeded.add( entry );
        }

        McpAudit.logBulkWrite( TOOL_NAME, rawList.size(), succeeded.size(), failed.size(), defaultAuthor );

        final Map< String, Object > out = new LinkedHashMap<>();
        out.put( "status", "completed" );
        out.put( "succeeded", succeeded );
        out.put( "failed", failed );
        out.put( "message", succeeded.size() + " of " + rawList.size() + " edge operations applied" );
        return McpToolUtils.jsonResult( McpToolUtils.SHARED_GSON, out );
    }

    private Map< String, Object > doUpsert( final Map< String, Object > op ) {
        final UUID src = parseUuid( op.get( "source_id" ) );
        final UUID tgt = parseUuid( op.get( "target_id" ) );
        final String rel = stringOrNull( op.get( "relationship_type" ) );
        if ( src == null || tgt == null || rel == null || rel.isBlank() ) {
            return Map.of( "error",
                    "upsert requires source_id, target_id, relationship_type" );
        }
        @SuppressWarnings( "unchecked" )
        final Map< String, Object > props = op.get( "properties" ) instanceof Map
                ? ( Map< String, Object > ) op.get( "properties" ) : Map.of();
        final KgCurationOps.EdgeResult r = ops.tryUpsertEdge( src, tgt, rel, props, defaultAuthor );
        return r.error().isPresent()
                ? Map.of( "error", r.error().get() )
                : Map.of( "id", r.edgeId().get().toString() );
    }

    private Map< String, Object > doConfirm( final Map< String, Object > op ) {
        final UUID id = parseUuid( op.get( "id" ) );
        if ( id == null ) return Map.of( "error", "confirm requires id (UUID)" );
        final Optional< String > err = ops.tryConfirmEdge( id, defaultAuthor );
        return err.isEmpty() ? Map.of( "id", id.toString() ) : Map.of( "id", id.toString(), "error", err.get() );
    }

    private Map< String, Object > doDelete( final Map< String, Object > op ) {
        final UUID id = parseUuid( op.get( "id" ) );
        if ( id == null ) return Map.of( "error", "delete requires id (UUID)" );
        final Optional< String > err = ops.tryDeleteEdge( id, defaultAuthor );
        return err.isEmpty() ? Map.of( "id", id.toString() ) : Map.of( "id", id.toString(), "error", err.get() );
    }

    private Map< String, Object > doDeleteAndReject( final Map< String, Object > op ) {
        final UUID id = parseUuid( op.get( "id" ) );
        final String reason = stringOrNull( op.get( "reason" ) );
        if ( id == null ) return Map.of( "error", "delete_and_reject requires id (UUID)" );
        if ( reason == null || reason.isBlank() ) return Map.of( "error", "reason is required for delete_and_reject" );
        final Optional< String > err = ops.tryDeleteAndRejectEdge( id, defaultAuthor, reason );
        return err.isEmpty() ? Map.of( "id", id.toString() ) : Map.of( "id", id.toString(), "error", err.get() );
    }

    private static String stringOrNull( final Object o ) { return o == null ? null : o.toString(); }
    private static UUID parseUuid( final Object o ) {
        if ( o == null ) return null;
        try { return UUID.fromString( o.toString() ); }
        catch ( final IllegalArgumentException e ) { return null; }
    }
    @SuppressWarnings( "unchecked" )
    private static Map< String, Object > castStringKey( final Map< ?, ? > raw ) { return ( Map< String, Object > ) raw; }
}
```

- [ ] **Step 4: Run, expect pass**

Run: `mvn -pl wikantik-admin-mcp -Dtest=CurateEdgesToolTest test`
Expected: 5 tests pass.

- [ ] **Step 5: Commit**

```bash
git add wikantik-admin-mcp/src/main/java/com/wikantik/mcp/tools/CurateEdgesTool.java \
        wikantik-admin-mcp/src/test/java/com/wikantik/mcp/tools/CurateEdgesToolTest.java
git commit -m "feat(mcp): curate_edges bulk heterogeneous edge curation tool"
```

---

## Task 13: Create `CurateNodesTool`

**Files:**
- Create: `wikantik-admin-mcp/src/main/java/com/wikantik/mcp/tools/CurateNodesTool.java`
- Create: `wikantik-admin-mcp/src/test/java/com/wikantik/mcp/tools/CurateNodesToolTest.java`

- [ ] **Step 1: Write the failing test class**

```java
package com.wikantik.mcp.tools;

import com.wikantik.api.knowledge.KgCurationOps;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

public class CurateNodesToolTest {

    private KgCurationOps ops;
    private CurateNodesTool tool;

    @BeforeEach void setUp() {
        ops = Mockito.mock( KgCurationOps.class );
        tool = new CurateNodesTool( ops, 50 );
        tool.setDefaultAuthor( "alice" );
    }

    @Test
    void upsertOpEchoesTagAndResultingNodeId() {
        final UUID nodeId = UUID.randomUUID();
        when( ops.tryUpsertNode( eq( "Raft" ), eq( "concept" ), eq( "PaxosAndRaft" ),
                any(), eq( "alice" ) ) )
                .thenReturn( KgCurationOps.NodeResult.ok( nodeId ) );

        final McpSchema.CallToolResult r = tool.execute( Map.of(
                "operations", List.of( Map.of(
                        "action", "upsert",
                        "tag", "node-1",
                        "name", "Raft",
                        "node_type", "concept",
                        "source_page", "PaxosAndRaft" ) ) ) );
        final String body = ( ( McpSchema.TextContent ) r.content().get( 0 ) ).text();
        assertTrue( body.contains( "\"tag\":\"node-1\"" ), body );
        assertTrue( body.contains( "\"id\":\"" + nodeId + "\"" ), body );
    }

    @Test
    void mergeSelfIsPerOpError() {
        final String id = UUID.randomUUID().toString();
        when( ops.tryMergeNodes( any(), any(), any() ) )
                .thenReturn( Optional.of( "source_id and target_id are the same" ) );

        final McpSchema.CallToolResult r = tool.execute( Map.of(
                "operations", List.of( Map.of(
                        "action", "merge",
                        "tag", "node-merge",
                        "source_id", id,
                        "target_id", id ) ) ) );
        final String body = ( ( McpSchema.TextContent ) r.content().get( 0 ) ).text();
        assertTrue( body.contains( "same" ), body );
        assertTrue( body.contains( "\"action\":\"merge\"" ), body );
    }

    @Test
    void upsertMissingRequiredNameIsPerOpError() {
        final McpSchema.CallToolResult r = tool.execute( Map.of(
                "operations", List.of( Map.of(
                        "action", "upsert", "tag", "x" ) ) ) );
        final String body = ( ( McpSchema.TextContent ) r.content().get( 0 ) ).text();
        assertTrue( body.contains( "name is required" )
                || body.contains( "requires name" ), body );
    }

    @Test
    void mergeServiceErrorSurfacesPerOp() {
        final UUID src = UUID.randomUUID();
        final UUID tgt = UUID.randomUUID();
        when( ops.tryMergeNodes( eq( src ), eq( tgt ), any() ) )
                .thenReturn( Optional.of( "merge constraint violation" ) );

        final McpSchema.CallToolResult r = tool.execute( Map.of(
                "operations", List.of( Map.of(
                        "action", "merge", "tag", "n1",
                        "source_id", src.toString(), "target_id", tgt.toString() ) ) ) );
        final String body = ( ( McpSchema.TextContent ) r.content().get( 0 ) ).text();
        assertTrue( body.contains( "merge constraint violation" ), body );
    }

    @Test
    void bulkLimitExceededIsTopLevelError() {
        final List< Object > ops51 = new java.util.ArrayList<>();
        for ( int i = 0; i < 51; i++ ) ops51.add( Map.of( "action", "delete",
                "id", UUID.randomUUID().toString() ) );
        final McpSchema.CallToolResult r = tool.execute( Map.of( "operations", ops51 ) );
        assertTrue( r.isError() );
    }
}
```

- [ ] **Step 2: Run, expect compile fail**

Run: `mvn -pl wikantik-admin-mcp -Dtest=CurateNodesToolTest test-compile`

- [ ] **Step 3: Implement `CurateNodesTool`**

```java
package com.wikantik.mcp.tools;

import com.wikantik.api.knowledge.KgCurationOps;
import io.modelcontextprotocol.spec.McpSchema;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.*;

public class CurateNodesTool implements McpTool, AuthorConfigurable {

    private static final Logger LOG = LogManager.getLogger( CurateNodesTool.class );
    public static final String TOOL_NAME = "curate_nodes";

    private final KgCurationOps ops;
    private final int bulkLimit;
    private volatile String defaultAuthor = "admin";

    public CurateNodesTool( final KgCurationOps ops, final int bulkLimit ) {
        this.ops = ops;
        this.bulkLimit = bulkLimit;
    }

    @Override public String name() { return TOOL_NAME; }
    @Override public void setDefaultAuthor( final String author ) { this.defaultAuthor = author; }

    @Override public McpSchema.Tool definition() {
        final Map< String, Object > properties = new LinkedHashMap<>();
        properties.put( "operations", Map.of(
                "type", "array",
                "description", "1.." + bulkLimit + " heterogeneous node ops. Each item has `action` " +
                        "(upsert|delete|merge) plus action-specific fields and an optional `tag`.",
                "items", Map.of( "type", "object" )
        ) );

        final Map< String, Object > exampleOut = Map.of(
                "status", "completed",
                "succeeded", List.of( Map.of( "tag", "node-1", "action", "upsert",
                        "id", "8f3c2a1b-..." ) ),
                "failed", List.of(),
                "message", "1 of 1 node operations applied" );
        final Map< String, Object > outputSchema = new LinkedHashMap<>();
        outputSchema.put( "type", "object" );
        outputSchema.put( "examples", List.of( exampleOut ) );

        return McpSchema.Tool.builder()
                .name( TOOL_NAME )
                .description( "Bulk heterogeneous node curation. Actions: " +
                        "`upsert` (HUMAN_AUTHORED), `delete`, `merge` (source → target, frontmatter rewritten)." )
                .inputSchema( new McpSchema.JsonSchema( "object", properties,
                        List.of( "operations" ), null, null, null ) )
                .outputSchema( outputSchema )
                .annotations( new McpSchema.ToolAnnotations( null, false, true, true, null, null ) )
                .build();
    }

    @Override public McpSchema.CallToolResult execute( final Map< String, Object > args ) {
        final Object raw = args.get( "operations" );
        if ( !( raw instanceof List< ? > rawList ) || rawList.isEmpty() ) {
            return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON,
                    "operations is required and must be a non-empty array" );
        }
        if ( rawList.size() > bulkLimit ) {
            return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON,
                    "bulk limit exceeded: " + rawList.size() + " > " + bulkLimit );
        }

        final List< Map< String, Object > > succeeded = new ArrayList<>();
        final List< Map< String, Object > > failed = new ArrayList<>();

        for ( final Object opEl : rawList ) {
            if ( !( opEl instanceof Map< ?, ? > opMap ) ) {
                failed.add( Map.of( "error", "operation must be an object" ) );
                continue;
            }
            final Map< String, Object > op = castStringKey( opMap );
            final String tag = stringOrNull( op.get( "tag" ) );
            final String action = stringOrNull( op.get( "action" ) );

            final Map< String, Object > result;
            switch ( action == null ? "" : action ) {
                case "upsert" -> result = doUpsert( op );
                case "delete" -> result = doDelete( op );
                case "merge"  -> result = doMerge( op );
                default       -> result = Map.of( "error",
                        "Unsupported action '" + action + "' — supported: upsert, delete, merge" );
            }

            final Map< String, Object > entry = new LinkedHashMap<>();
            entry.put( "tag", tag );
            entry.put( "action", action );
            entry.putAll( result );
            if ( entry.containsKey( "error" ) ) failed.add( entry );
            else succeeded.add( entry );
        }

        McpAudit.logBulkWrite( TOOL_NAME, rawList.size(), succeeded.size(), failed.size(), defaultAuthor );

        final Map< String, Object > out = new LinkedHashMap<>();
        out.put( "status", "completed" );
        out.put( "succeeded", succeeded );
        out.put( "failed", failed );
        out.put( "message", succeeded.size() + " of " + rawList.size() + " node operations applied" );
        return McpToolUtils.jsonResult( McpToolUtils.SHARED_GSON, out );
    }

    private Map< String, Object > doUpsert( final Map< String, Object > op ) {
        final String name = stringOrNull( op.get( "name" ) );
        if ( name == null || name.isBlank() ) return Map.of( "error", "upsert requires name" );
        final String nodeType = stringOrNull( op.get( "node_type" ) );
        final String sourcePage = stringOrNull( op.get( "source_page" ) );
        @SuppressWarnings( "unchecked" )
        final Map< String, Object > props = op.get( "properties" ) instanceof Map
                ? ( Map< String, Object > ) op.get( "properties" ) : Map.of();
        final KgCurationOps.NodeResult r = ops.tryUpsertNode( name, nodeType, sourcePage, props, defaultAuthor );
        return r.error().isPresent()
                ? Map.of( "error", r.error().get() )
                : Map.of( "id", r.nodeId().get().toString() );
    }

    private Map< String, Object > doDelete( final Map< String, Object > op ) {
        final UUID id = parseUuid( op.get( "id" ) );
        if ( id == null ) return Map.of( "error", "delete requires id (UUID)" );
        final Optional< String > err = ops.tryDeleteNode( id, defaultAuthor );
        return err.isEmpty() ? Map.of( "id", id.toString() ) : Map.of( "id", id.toString(), "error", err.get() );
    }

    private Map< String, Object > doMerge( final Map< String, Object > op ) {
        final UUID src = parseUuid( op.get( "source_id" ) );
        final UUID tgt = parseUuid( op.get( "target_id" ) );
        if ( src == null || tgt == null ) return Map.of( "error", "merge requires source_id and target_id (UUIDs)" );
        final Optional< String > err = ops.tryMergeNodes( src, tgt, defaultAuthor );
        return err.isEmpty()
                ? Map.of( "source_id", src.toString(), "target_id", tgt.toString() )
                : Map.of( "source_id", src.toString(), "target_id", tgt.toString(), "error", err.get() );
    }

    private static String stringOrNull( final Object o ) { return o == null ? null : o.toString(); }
    private static UUID parseUuid( final Object o ) {
        if ( o == null ) return null;
        try { return UUID.fromString( o.toString() ); }
        catch ( final IllegalArgumentException e ) { return null; }
    }
    @SuppressWarnings( "unchecked" )
    private static Map< String, Object > castStringKey( final Map< ?, ? > raw ) { return ( Map< String, Object > ) raw; }
}
```

- [ ] **Step 4: Run, expect pass**

Run: `mvn -pl wikantik-admin-mcp -Dtest=CurateNodesToolTest test`
Expected: 5 tests pass.

- [ ] **Step 5: Commit**

```bash
git add wikantik-admin-mcp/src/main/java/com/wikantik/mcp/tools/CurateNodesTool.java \
        wikantik-admin-mcp/src/test/java/com/wikantik/mcp/tools/CurateNodesToolTest.java
git commit -m "feat(mcp): curate_nodes bulk heterogeneous node curation tool"
```

---

## Task 13b: Surface `kg_excluded_pages` warning on approve

Spec §6 edge cases: when a proposal's source page is on `kg_excluded_pages`, the
approval still succeeds but the response payload carries
`warnings: ["source_page is in kg_excluded_pages list"]`. This requires plumbing
warnings through the facade.

**Files:**
- Modify: `wikantik-api/src/main/java/com/wikantik/api/knowledge/KgCurationOps.java`
- Modify: `wikantik-main/src/main/java/com/wikantik/knowledge/curation/DefaultKgCurationOps.java`
- Modify: `wikantik-main/src/test/java/com/wikantik/knowledge/curation/DefaultKgCurationOpsTest.java`
- Modify: `wikantik-admin-mcp/src/main/java/com/wikantik/mcp/tools/ReviewProposalsTool.java`
- Modify: `wikantik-admin-mcp/src/test/java/com/wikantik/mcp/tools/ReviewProposalsToolTest.java`
- Modify: `wikantik-rest/src/main/java/com/wikantik/rest/AdminKnowledgeResource.java`

- [ ] **Step 1: Failing test — facade returns warning when source page is excluded**

Append to `DefaultKgCurationOpsTest`:

```java
@Test
void approveSurfacesKgExcludedPagesWarningWhenPageIsOnExclusionList() {
    final com.wikantik.knowledge.extraction.KgExcludedPagesRepository excluded =
            Mockito.mock( com.wikantik.knowledge.extraction.KgExcludedPagesRepository.class );
    ops = new DefaultKgCurationOps( kg, pages, saver, excluded );

    final UUID id = UUID.randomUUID();
    final KgProposal approved = Mockito.mock( KgProposal.class );
    when( approved.proposalType() ).thenReturn( "new-node" );
    when( approved.sourcePage() ).thenReturn( "PaxosAndRaft" );
    when( kg.approveProposal( eq( id ), any() ) ).thenReturn( approved );
    when( excluded.findReason( "PaxosAndRaft" ) )
            .thenReturn( Optional.of( "system-page" ) );

    final KgCurationOps.ApproveOutcome r = ops.tryApprove( id, "alice" );
    assertTrue( r.error().isEmpty() );
    assertEquals( List.of( "source_page is in kg_excluded_pages list" ), r.warnings() );
}

@Test
void approveHasNoWarningsWhenSourcePageNotExcluded() {
    final com.wikantik.knowledge.extraction.KgExcludedPagesRepository excluded =
            Mockito.mock( com.wikantik.knowledge.extraction.KgExcludedPagesRepository.class );
    ops = new DefaultKgCurationOps( kg, pages, saver, excluded );

    final UUID id = UUID.randomUUID();
    final KgProposal approved = Mockito.mock( KgProposal.class );
    when( approved.proposalType() ).thenReturn( "new-node" );
    when( approved.sourcePage() ).thenReturn( "Active" );
    when( kg.approveProposal( eq( id ), any() ) ).thenReturn( approved );
    when( excluded.findReason( "Active" ) ).thenReturn( Optional.empty() );

    final KgCurationOps.ApproveOutcome r = ops.tryApprove( id, "alice" );
    assertTrue( r.warnings().isEmpty() );
}
```

- [ ] **Step 2: Run, expect compile fail** (constructor + method missing)

Run: `mvn -pl wikantik-main -Dtest=DefaultKgCurationOpsTest test-compile`

- [ ] **Step 3: Extend `KgCurationOps`**

```java
record ApproveOutcome( Optional<String> error, java.util.List<String> warnings ) {
    public static ApproveOutcome ok() { return new ApproveOutcome( Optional.empty(), java.util.List.of() ); }
    public static ApproveOutcome ok( java.util.List<String> warnings ) {
        return new ApproveOutcome( Optional.empty(), warnings );
    }
    public static ApproveOutcome fail( String msg ) {
        return new ApproveOutcome( Optional.of( msg ), java.util.List.of() );
    }
}

ApproveOutcome tryApprove( UUID proposalId, String reviewedBy );
```

Keep the existing `tryApproveProposal( UUID, String )` method as a default that
discards warnings, so any caller that doesn't care can still use the simpler
signature:

```java
default Optional<String> tryApproveProposal( UUID id, String by ) {
    return tryApprove( id, by ).error();
}
```

- [ ] **Step 4: Implement `tryApprove` in `DefaultKgCurationOps`**

Add a new constructor that accepts `KgExcludedPagesRepository excluded` (a
required dependency for the warning path), and update `KnowledgeSubsystemFactory`
(in Task 5 wiring) to pass the repo through. Add the method:

```java
@Override
public KgCurationOps.ApproveOutcome tryApprove( final UUID proposalId, final String reviewedBy ) {
    try {
        final KgProposal approved = kg.approveProposal( proposalId, reviewedBy );
        if ( approved == null ) return KgCurationOps.ApproveOutcome.fail( "Not found: " + proposalId );
        writeFrontmatterIfEdge( approved );

        final java.util.List< String > warnings = new java.util.ArrayList<>();
        if ( excluded != null && approved.sourcePage() != null
                && excluded.findReason( approved.sourcePage() ).isPresent() ) {
            warnings.add( "source_page is in kg_excluded_pages list" );
        }
        return KgCurationOps.ApproveOutcome.ok( java.util.List.copyOf( warnings ) );
    } catch ( final Exception e ) {
        LOG.warn( "tryApprove: proposal={} actor={}: {}", proposalId, reviewedBy, e.getMessage() );
        return KgCurationOps.ApproveOutcome.fail( e.getMessage() != null ? e.getMessage() : "Internal error" );
    }
}
```

Add a four-arg constructor `DefaultKgCurationOps( kg, pages, saver, excluded )`
alongside the existing three-arg one (which defaults `excluded` to `null` — warning
silently disabled, used by older tests).

- [ ] **Step 5: Route warnings through `ReviewProposalsTool`**

In `ReviewProposalsTool.execute(...)`, change the `approve` branch:

```java
case "approve" -> {
    final KgCurationOps.ApproveOutcome o = ops.tryApprove( id, defaultAuthor );
    if ( o.error().isPresent() ) {
        final Map< String, Object > f = new LinkedHashMap<>();
        f.put( "id", idStr );
        f.put( "error", o.error().get() );
        failed.add( f );
    } else {
        succeeded.add( idStr );
        if ( !o.warnings().isEmpty() ) {
            warningsByProposal.put( idStr, o.warnings() );
        }
    }
}
```

Where `warningsByProposal` is a `LinkedHashMap<String, List<String>>` declared
at the top of `execute(...)`. At the end, only include `warnings_by_proposal` in
the response when non-empty:

```java
if ( !warningsByProposal.isEmpty() ) out.put( "warnings_by_proposal", warningsByProposal );
```

Update the `ReviewProposalsToolTest` happy-path approval to verify the
field appears with the seeded warning, and confirm it is *absent* when no
warnings are produced.

- [ ] **Step 6: Update REST to use the new outcome shape**

`AdminKnowledgeResource` already routes through the facade after Task 7; replace
the single-id approve handler's `Optional<String>` consumption with
`ops.tryApprove(...)`, attaching `warnings` to the JSON response (`{ "approved": true, "warnings": [...] }`).
The bulk-action handler does the same and adds a `warnings_by_proposal` map
when any approved proposal raised a warning.

- [ ] **Step 7: Run unit reactor**

Run: `mvn -pl wikantik-main,wikantik-admin-mcp,wikantik-rest test`
Expected: BUILD SUCCESS; both new tests pass; existing tests pass with the
default-method bridge.

- [ ] **Step 8: Commit**

```bash
git add wikantik-api/src/main/java/com/wikantik/api/knowledge/KgCurationOps.java \
        wikantik-main/src/main/java/com/wikantik/knowledge/curation/DefaultKgCurationOps.java \
        wikantik-main/src/test/java/com/wikantik/knowledge/curation/DefaultKgCurationOpsTest.java \
        wikantik-admin-mcp/src/main/java/com/wikantik/mcp/tools/ReviewProposalsTool.java \
        wikantik-admin-mcp/src/test/java/com/wikantik/mcp/tools/ReviewProposalsToolTest.java \
        wikantik-rest/src/main/java/com/wikantik/rest/AdminKnowledgeResource.java
git commit -m "feat(kg-curation): surface kg_excluded_pages warning on approve"
```

---

## Task 14: Register the four new tools

**Files:**
- Modify: `wikantik-admin-mcp/src/main/java/com/wikantik/mcp/McpToolRegistry.java`
- Modify: `wikantik-admin-mcp/src/test/java/com/wikantik/mcp/McpToolRegistryTest.java`

- [ ] **Step 1: Failing test — registry should expose 22 tools when KG is available**

In `McpToolRegistryTest`, find the existing assertion that counts read-only + author-configurable tools when `kgService` is provided. Update the expected counts (or list of names) to include `inspect_proposals`, `review_proposals`, `curate_edges`, `curate_nodes`. The test must fail before code changes.

- [ ] **Step 2: Run, expect fail**

Run: `mvn -pl wikantik-admin-mcp -Dtest=McpToolRegistryTest test`

- [ ] **Step 3: Wire the new tools in `McpToolRegistry`**

In the `if ( kgService != null ) { ... }` block (line ~113), pull the curation facade from the bridge and add the new tools:

```java
final KgCurationOps curation = com.wikantik.knowledge.subsystem
        .KnowledgeSubsystemBridge.fromLegacyEngine( engine ).kgCurationOps();
final int bulkLimit = new McpConfig().kgCurationBulkLimit();

readOnlyList.add( new ListProposalsTool( kgService ) );
readOnlyList.add( new InspectProposalsTool( kgService, bulkLimit ) );

authorConfigurableList.add( new ProposeKnowledgeTool( kgService ) );
authorConfigurableList.add( new ReviewProposalsTool( curation, bulkLimit ) );  // future: gated by kg_curate scope
authorConfigurableList.add( new CurateEdgesTool(    curation, bulkLimit ) );  // future: gated by kg_curate scope
authorConfigurableList.add( new CurateNodesTool(    curation, bulkLimit ) );  // future: gated by kg_curate scope
```

If the bridge returns `null` (engine without knowledge), do not register the curation writers either (currently the whole block is already guarded by `kgService != null`).

- [ ] **Step 4: Run, expect pass**

Run: `mvn -pl wikantik-admin-mcp -Dtest=McpToolRegistryTest test`
Expected: registry test passes.

- [ ] **Step 5: Commit**

```bash
git add wikantik-admin-mcp/src/main/java/com/wikantik/mcp/McpToolRegistry.java \
        wikantik-admin-mcp/src/test/java/com/wikantik/mcp/McpToolRegistryTest.java
git commit -m "feat(mcp): register inspect/review/curate-edges/curate-nodes tools"
```

---

## Task 15: Update `McpProtocolIT.EXPECTED_TOOLS` (Cargo IT)

**Files:**
- Modify: `wikantik-it-tests/wikantik-selenide-tests/src/main/java/com/wikantik/its/mcp/McpProtocolIT.java`

- [ ] **Step 1: Add the four names to the existing set**

```java
private static final Set< String > EXPECTED_TOOLS = Set.of(
        // ... existing entries unchanged ...
        "list_proposals", "inspect_proposals", "review_proposals",
        "curate_edges", "curate_nodes", "propose_knowledge",
        "mark_page_verified"
);
```

(Make sure the resulting set has exactly 22 elements.)

- [ ] **Step 2: Commit (no test run yet — ITs run together in Task 17)**

```bash
git add wikantik-it-tests/wikantik-selenide-tests/src/main/java/com/wikantik/its/mcp/McpProtocolIT.java
git commit -m "test(it): expect KG curation tools in MCP tool list"
```

---

## Task 16: Wire-level Cargo IT for KG curation

**Files:**
- Create: `wikantik-it-tests/wikantik-selenide-tests/src/main/java/com/wikantik/its/mcp/KgCurationIT.java`

- [ ] **Step 1: Write the IT**

```java
package com.wikantik.its.mcp;

import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/**
 * Wire-level Cargo IT for KG curation tools. Drives the JSON-RPC contract end-to-end,
 * asserts response envelope shape, and verifies per-op error messages cite reasons.
 *
 * <p>Pre-conditions for the test fixture: seed one pending new-edge proposal and one
 * pending new-node proposal at IT-setup time; capture their UUIDs as static fields.
 */
public class KgCurationIT extends WithMcpTestSetup {

    @Test
    public void listProposalsIncludesConflictFlags() {
        final McpSchema.CallToolResult result = mcp.callTool( "list_proposals",
                Map.of( "status", "pending", "limit", 10 ) );
        Assertions.assertFalse( result.isError(), "list_proposals should succeed" );
        final String body = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        Assertions.assertTrue( body.contains( "node_exists" )
                        || body.contains( "edge_previously_rejected" ),
                "list_proposals payload should include conflict flag fields: " + body );
    }

    @Test
    public void inspectProposalsResolvesKnownIdsAndMissesUnknown() {
        final String knownId = WithMcpTestSetup.seededPendingNodeProposalId();
        final String fakeId = "00000000-0000-0000-0000-000000000000";
        final McpSchema.CallToolResult result = mcp.callTool( "inspect_proposals",
                Map.of( "ids", List.of( knownId, fakeId, "not-a-uuid" ) ) );
        Assertions.assertFalse( result.isError() );
        final String body = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        Assertions.assertTrue( body.contains( knownId ), body );
        Assertions.assertTrue( body.contains( "\"missing\":" ), body );
        Assertions.assertTrue( body.contains( fakeId ) && body.contains( "not-a-uuid" ),
                "Both fake UUID and invalid UUID should be in missing[]: " + body );
    }

    @Test
    public void reviewProposalsApproveSurfacesPerIdErrors() {
        final String good = WithMcpTestSetup.seededPendingNodeProposalId();
        final String missing = "00000000-0000-0000-0000-000000000000";
        final McpSchema.CallToolResult result = mcp.callTool( "review_proposals",
                Map.of( "verdict", "approve", "ids", List.of( good, missing ) ) );
        final String body = ( ( McpSchema.TextContent ) result.content().get( 0 ) ).text();
        Assertions.assertTrue( body.contains( "\"succeeded\"" ) && body.contains( good ), body );
        Assertions.assertTrue( body.contains( "Not found" ) && body.contains( missing ), body );
    }

    @Test
    public void reviewProposalsRejectWithoutReasonIsTopLevelError() {
        final McpSchema.CallToolResult result = mcp.callTool( "review_proposals",
                Map.of( "verdict", "reject", "ids", List.of(
                        WithMcpTestSetup.seededPendingEdgeProposalId() ) ) );
        Assertions.assertTrue( result.isError() );
    }

    @Test
    public void curateEdgesConfirmThenDeleteFlowsThroughEnvelope() {
        final String edgeId = WithMcpTestSetup.seededEdgeId();
        final McpSchema.CallToolResult confirm = mcp.callTool( "curate_edges",
                Map.of( "operations", List.of( Map.of(
                        "action", "confirm", "tag", "e1", "id", edgeId ) ) ) );
        Assertions.assertFalse( confirm.isError() );
        final String confirmBody = ( ( McpSchema.TextContent ) confirm.content().get( 0 ) ).text();
        Assertions.assertTrue( confirmBody.contains( "\"tag\":\"e1\"" ), confirmBody );
        Assertions.assertTrue( confirmBody.contains( "\"succeeded\"" ), confirmBody );

        final McpSchema.CallToolResult delete = mcp.callTool( "curate_edges",
                Map.of( "operations", List.of( Map.of(
                        "action", "delete", "tag", "e2", "id", edgeId ) ) ) );
        Assertions.assertFalse( delete.isError() );
    }

    @Test
    public void curateNodesMergeSelfRejectsAsPerOpError() {
        final String nodeId = WithMcpTestSetup.seededNodeId();
        final McpSchema.CallToolResult r = mcp.callTool( "curate_nodes",
                Map.of( "operations", List.of( Map.of(
                        "action", "merge", "tag", "n1",
                        "source_id", nodeId, "target_id", nodeId ) ) ) );
        final String body = ( ( McpSchema.TextContent ) r.content().get( 0 ) ).text();
        Assertions.assertTrue( body.contains( "same" ), body );
        Assertions.assertTrue( body.contains( "\"failed\"" ), body );
    }

    @Test
    public void bulkLimitExceededReturnsTopLevelError() {
        final java.util.List< Object > ops = new java.util.ArrayList<>();
        for ( int i = 0; i < 51; i++ ) ops.add( Map.of( "action", "confirm",
                "id", "00000000-0000-0000-0000-000000000000" ) );
        final McpSchema.CallToolResult r = mcp.callTool( "curate_edges",
                Map.of( "operations", ops ) );
        Assertions.assertTrue( r.isError() );
    }

    @Test
    public void reviewProposalsEmitsBulkAuditLogLine() throws Exception {
        // Verify the McpAudit row by tailing catalina.out written during the Cargo run.
        // WithMcpTestSetup exposes catalinaOutPath() for ITs that need to confirm log emission.
        final long before = java.nio.file.Files.size( WithMcpTestSetup.catalinaOutPath() );
        mcp.callTool( "review_proposals", Map.of(
                "verdict", "approve",
                "ids", List.of( WithMcpTestSetup.seededPendingNodeProposalId() ) ) );
        final String tail = WithMcpTestSetup.readCatalinaOutSince( before );
        Assertions.assertTrue( tail.contains( "tool=review_proposals action=bulk" ),
                "Expected McpAudit bulk-write log line, got: " + tail );
        Assertions.assertTrue( tail.contains( "attempted=1" ), tail );
    }
}
```

- [ ] **Step 2: Add helpers to `WithMcpTestSetup`**

`WithMcpTestSetup` already provides the `mcp` client and a Cargo-launched Tomcat against a clean PostgreSQL. Add static accessors for seed UUIDs and catalina log access (open the file and add):

```java
public static String seededPendingNodeProposalId() { /* return a UUID seeded by @BeforeAll fixture */ }
public static String seededPendingEdgeProposalId() { /* ... */ }
public static String seededEdgeId() { /* HUMAN_CURATED edge seeded for confirm/delete */ }
public static String seededNodeId() { /* node seeded for merge-self test */ }

public static java.nio.file.Path catalinaOutPath() {
    return java.nio.file.Path.of( System.getProperty( "cargo.tomcat.catalina.out",
            "target/cargo/configurations/tomcat11x/logs/catalina.out" ) );
}

public static String readCatalinaOutSince( final long offsetBytes ) throws java.io.IOException {
    final java.nio.file.Path p = catalinaOutPath();
    try ( final java.io.RandomAccessFile raf = new java.io.RandomAccessFile( p.toFile(), "r" ) ) {
        raf.seek( offsetBytes );
        final byte[] buf = new byte[ ( int ) ( raf.length() - offsetBytes ) ];
        raf.readFully( buf );
        return new String( buf, java.nio.charset.StandardCharsets.UTF_8 );
    }
}
```

The seed fixture (in the existing `@BeforeAll` chain) issues an `INSERT … RETURNING id` per seed via the same JDBC connection the suite already uses. If the suite has no SQL hook today, add a helper `seedKgCurationFixtures()` and call it from `@BeforeAll`. Confirm the Cargo configuration directory matches the suite's actual layout — if the project uses a different `cargo.tomcat.catalina.out` path, supply it via `<systemPropertyVariables>` in `pom.xml` for this IT.

- [ ] **Step 3: Run the integration test reactor**

Per CLAUDE.md, run integration tests sequentially:

Run: `mvn clean install -Pintegration-tests -fae -pl wikantik-it-tests/wikantik-selenide-tests -am`
Expected: all ITs in `KgCurationIT` plus `McpProtocolIT` pass; other ITs unaffected.

- [ ] **Step 4: Commit**

```bash
git add wikantik-it-tests/wikantik-selenide-tests/src/main/java/com/wikantik/its/mcp/KgCurationIT.java \
        wikantik-it-tests/wikantik-selenide-tests/src/main/java/com/wikantik/its/mcp/WithMcpTestSetup.java
git commit -m "test(it): wire-level Cargo IT for KG curation MCP tools"
```

---

## Task 17: Update `CLAUDE.md` and cross-link from `KgInclusionPolicy.md`

**Files:**
- Modify: `CLAUDE.md`
- Modify: `docs/wikantik-pages/KgInclusionPolicy.md`

- [ ] **Step 1: Bump the count in `CLAUDE.md`**

Find the agent-facing surface table in `CLAUDE.md` (section "Agent-facing surface summary") and update the `/wikantik-admin-mcp` row:

```
| `/wikantik-admin-mcp` | wikantik-admin-mcp | MCP (Streamable HTTP) | 22 write/analytics tools (incl. KG curation) | `McpAccessFilter` (bearer token / API key) |
```

Also update the inline mentions of "18 tools" in the prose ("D28: counts reconciled with the live registry 2026-04-25") — replace with "22 tools (incl. inspect_proposals, review_proposals, curate_edges, curate_nodes) — reconciled 2026-05-13".

- [ ] **Step 2: Cross-link from `KgInclusionPolicy.md`**

Add a short paragraph under an "Agent curation path" subsection of `KgInclusionPolicy.md` pointing agents at the new MCP tools as the preferred path for triaging proposals:

```markdown
## Agent curation path

Curator agents should drive proposal triage through `/wikantik-admin-mcp` rather
than the REST surface:

- `list_proposals` — filtered listing with conflict flags
  (`node_exists`, `edge_previously_rejected`)
- `inspect_proposals` — bulk deep-dive (1..50 ids) with prior reviews
- `review_proposals` — bulk `approve | reject | judge` (1..50 ids; `reject`
  requires a top-level `reason`). Approvals whose source page sits on the
  exclusion list still succeed but echo a `warnings` entry for that proposal.
- `curate_edges` / `curate_nodes` — heterogeneous bulk ops (1..50 ops).

See `docs/superpowers/specs/2026-05-13-kg-curation-mcp-design.md` for the
full envelope and error contract.
```

- [ ] **Step 3: Commit**

```bash
git add CLAUDE.md docs/wikantik-pages/KgInclusionPolicy.md
git commit -m "docs: bump /wikantik-admin-mcp tool count + cross-link from KgInclusionPolicy"
```

---

## Task 18: Final full integration build

**Files:** none (build verification)

- [ ] **Step 1: Run the unit-test reactor**

Run: `mvn clean install -T 1C -DskipITs`
Expected: BUILD SUCCESS. Any compile/test failure in another module (e.g., a positional `Services` record constructor missed in Task 5) shows up here.

- [ ] **Step 2: Run the integration test reactor sequentially**

Run: `mvn clean install -Pintegration-tests -fae`
Expected: BUILD SUCCESS. Do NOT use `-T` for ITs per CLAUDE.md.

- [ ] **Step 3: Verify the live MCP surface count**

Once the local Tomcat is running, hit the MCP `listTools` JSON-RPC method (or run the `McpProtocolIT.listToolsReturnsAllTools` test in isolation) and confirm exactly 22 tool names. If anything's missing or extra, fix in-place — do not commit a passing build with a drifted surface.

- [ ] **Step 4: Update the memory pointer**

Update `/home/jakefear/.claude/projects/-home-jakefear-source-jspwiki/memory/project_admin_mcp_tool_surface.md` to reflect the new count (18 → 22) and list the four new tool names. This is the memory file already noted in CLAUDE.md as the authoritative live tool count — keep it locked to reality.

- [ ] **Step 5: (Manual operator step, not part of the commit)**

Smoke-test the new tools against the local Tomcat using the testbot API key from `test.properties`:

```bash
# Tool list
curl -s -H "Authorization: Bearer $(cat tomcat/tomcat-11/lib/wikantik-custom.properties | grep mcp.access.keys | cut -d= -f2 | tr -d ' ')" \
     -H 'Content-Type: application/json' \
     -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}' \
     http://localhost:8080/wikantik-admin-mcp | jq '.result.tools | length'
# Expected: 22
```

---

## Self-Review

Done after writing — see plan-self-review task in the brainstorming flow. Inline fixes only; the plan is the artifact.
