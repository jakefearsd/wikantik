# Knowledge Graph Dogfooding Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Document the KG system and all Wikantik development features as wiki pages with rich frontmatter relationships, build a RelationshipsPlugin for in-page navigation, and add status filtering to the Node Explorer.

**Architecture:** Three workstreams executed in dependency order: (1) backend extensions — status filter on node queries and a new RelationshipsPlugin, (2) frontend — status dropdown in Node Explorer, (3) content — 14 new wiki pages and 9 updated pages with corrected statuses and new relationship types.

**Tech Stack:** Java 21, JUnit 5, H2 (tests), PostgreSQL (production), React, Flexmark plugin system, YAML frontmatter

**Design spec:** `docs/superpowers/specs/2026-04-05-kg-dogfooding-design.md`

---

### Task 1: Add status filter to node query — repository layer

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/knowledge/JdbcKnowledgeRepository.java:171-223`
- Test: `wikantik-main/src/test/java/com/wikantik/knowledge/JdbcKnowledgeRepositoryTest.java`

- [ ] **Step 1: Write the failing test**

Add to `JdbcKnowledgeRepositoryTest.java` after the existing `getNodeNames_handlesEmptySet` test:

```java
// --- Status filter tests ---

@Test
void queryNodes_filtersByStatus() {
    repo.upsertNode( "DeployedFeature", "article", "DeployedFeature.md",
            Provenance.HUMAN_AUTHORED, Map.of( "status", "deployed" ) );
    repo.upsertNode( "DesignedFeature", "article", "DesignedFeature.md",
            Provenance.HUMAN_AUTHORED, Map.of( "status", "designed" ) );
    repo.upsertNode( "ActiveFeature", "article", "ActiveFeature.md",
            Provenance.HUMAN_AUTHORED, Map.of( "status", "active" ) );

    final Map< String, Object > filters = Map.of( "status", "deployed" );
    final List< KgNode > results = repo.queryNodes( filters, null, 50, 0 );
    assertEquals( 1, results.size() );
    assertEquals( "DeployedFeature", results.get( 0 ).name() );
}

@Test
void queryNodes_statusFilterCombinesWithTypeFilter() {
    repo.upsertNode( "A", "article", null,
            Provenance.HUMAN_AUTHORED, Map.of( "status", "deployed" ) );
    repo.upsertNode( "B", "hub", null,
            Provenance.HUMAN_AUTHORED, Map.of( "status", "deployed" ) );
    repo.upsertNode( "C", "article", null,
            Provenance.HUMAN_AUTHORED, Map.of( "status", "designed" ) );

    final Map< String, Object > filters = Map.of( "status", "deployed", "node_type", "article" );
    final List< KgNode > results = repo.queryNodes( filters, null, 50, 0 );
    assertEquals( 1, results.size() );
    assertEquals( "A", results.get( 0 ).name() );
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn test -pl wikantik-main -Dtest=JdbcKnowledgeRepositoryTest#queryNodes_filtersByStatus,JdbcKnowledgeRepositoryTest#queryNodes_statusFilterCombinesWithTypeFilter`

Expected: Tests fail because the `status` filter key is not handled in `queryNodes()`.

- [ ] **Step 3: Implement status filter in queryNodes**

In `JdbcKnowledgeRepository.java`, inside the `queryNodes()` method (line 177, within the `if( filters != null )` block), add after the `name` filter (after line 189):

```java
            if( filters.containsKey( "status" ) ) {
                if( isPostgreSQL ) {
                    sql.append( " AND properties->>'status' = ?" );
                } else {
                    sql.append( " AND LOWER( CAST( properties AS VARCHAR ) ) LIKE ?" );
                }
                if( isPostgreSQL ) {
                    params.add( filters.get( "status" ) );
                } else {
                    params.add( "%\"status\":\"" + filters.get( "status" ).toString().toLowerCase() + "\"%"  );
                }
            }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn test -pl wikantik-main -Dtest=JdbcKnowledgeRepositoryTest`

Expected: All tests pass including the two new ones.

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/knowledge/JdbcKnowledgeRepository.java \
       wikantik-main/src/test/java/com/wikantik/knowledge/JdbcKnowledgeRepositoryTest.java
git commit -m "feat(knowledge): add status property filter to node queries"
```

---

### Task 2: Add distinct status values to schema discovery

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/knowledge/DefaultKnowledgeGraphService.java:51-88`
- Modify: `wikantik-api/src/main/java/com/wikantik/api/knowledge/SchemaDescription.java`
- Test: `wikantik-rest/src/test/java/com/wikantik/rest/AdminKnowledgeResourceTest.java`

The `discoverSchema()` method already iterates all nodes to collect property stats (line 58-70). Collect distinct status values during that same loop — no new repo method needed, no H2 dialect issues.

- [ ] **Step 1: Write the failing test**

Add to `AdminKnowledgeResourceTest.java`:

```java
@Test
void testSchemaIncludesStatusValues() throws Exception {
    final KnowledgeGraphService service = engine.getManager( KnowledgeGraphService.class );
    service.upsertNode( "X", "article", null,
            Provenance.HUMAN_AUTHORED, Map.of( "status", "deployed" ) );
    service.upsertNode( "Y", "article", null,
            Provenance.HUMAN_AUTHORED, Map.of( "status", "designed" ) );

    final String json = doGet( "/schema" );
    final JsonObject schema = gson.fromJson( json, JsonObject.class );
    assertFalse( schema.has( "error" ), "Should not be an error: " + json );
    final JsonArray statuses = schema.getAsJsonArray( "statusValues" );
    assertNotNull( statuses, "Schema should have statusValues" );
    assertTrue( statuses.size() >= 2, "Should have at least 2 status values" );
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl wikantik-rest -Dtest=AdminKnowledgeResourceTest#testSchemaIncludesStatusValues`

Expected: Fails — `statusValues` field doesn't exist in schema response.

- [ ] **Step 3: Add statusValues to SchemaDescription**

Replace `wikantik-api/src/main/java/com/wikantik/api/knowledge/SchemaDescription.java`:

```java
package com.wikantik.api.knowledge;

import java.util.List;
import java.util.Map;

public record SchemaDescription(
    List< String > nodeTypes,
    List< String > relationshipTypes,
    List< String > statusValues,
    Map< String, PropertyInfo > propertyKeys,
    Stats stats
) {
    public record PropertyInfo( long count, List< String > sampleValues ) {}
    public record Stats( long nodes, long edges, long unreviewedProposals ) {}
}
```

- [ ] **Step 4: Collect status values in discoverSchema**

In `DefaultKnowledgeGraphService.java`, in `discoverSchema()`, add a `TreeSet` before the node loop (after line 57):

```java
        final Set< String > statusSet = new TreeSet<>();
```

Inside the node property loop (after line 68, inside the `for( final Map.Entry ... )` block), add:

```java
                    if( "status".equals( key ) && entry.getValue() != null ) {
                        statusSet.add( entry.getValue().toString() );
                    }
```

Update the `return new SchemaDescription(...)` call to include `new ArrayList<>( statusSet )`:

```java
        return new SchemaDescription(
                nodeTypes,
                relTypes,
                new ArrayList<>( statusSet ),
                propertyKeys,
                new SchemaDescription.Stats( nodeCount, edgeCount, pendingCount )
        );
```

Add `import java.util.TreeSet;` if not already imported (existing `import java.util.*;` covers it).

- [ ] **Step 5: Build API module first, then run tests**

Run:
```bash
mvn install -pl wikantik-api -Dmaven.test.skip
mvn test -pl wikantik-rest -Dtest=AdminKnowledgeResourceTest
```

Expected: All tests pass including the new schema test.

- [ ] **Step 6: Commit**

```bash
git add wikantik-api/src/main/java/com/wikantik/api/knowledge/SchemaDescription.java \
       wikantik-main/src/main/java/com/wikantik/knowledge/DefaultKnowledgeGraphService.java \
       wikantik-rest/src/test/java/com/wikantik/rest/AdminKnowledgeResourceTest.java
git commit -m "feat(knowledge): add distinct status values to schema discovery"
```

---

### Task 3: Add status filter to REST endpoint

**Files:**
- Modify: `wikantik-rest/src/main/java/com/wikantik/rest/AdminKnowledgeResource.java:197-208`
- Test: `wikantik-rest/src/test/java/com/wikantik/rest/AdminKnowledgeResourceTest.java`

- [ ] **Step 1: Write the failing test**

Add to `AdminKnowledgeResourceTest.java`:

```java
@Test
void testListNodesFiltersByStatus() throws Exception {
    final KnowledgeGraphService service = engine.getManager( KnowledgeGraphService.class );
    service.upsertNode( "DeployedPage", "article", "DeployedPage.md",
            Provenance.HUMAN_AUTHORED, Map.of( "status", "deployed" ) );
    service.upsertNode( "DesignedPage", "article", "DesignedPage.md",
            Provenance.HUMAN_AUTHORED, Map.of( "status", "designed" ) );

    final String json = doGetWithParams( "/nodes",
            Map.of( "status", "deployed", "limit", "50" ) );
    final JsonObject result = gson.fromJson( json, JsonObject.class );
    assertFalse( result.has( "error" ), "Should not be an error: " + json );
    final JsonArray nodes = result.getAsJsonArray( "nodes" );
    assertEquals( 1, nodes.size(), "Should find only the deployed node" );
    assertEquals( "DeployedPage", nodes.get( 0 ).getAsJsonObject().get( "name" ).getAsString() );
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl wikantik-rest -Dtest=AdminKnowledgeResourceTest#testListNodesFiltersByStatus`

Expected: Fails — status parameter is ignored, returns both nodes.

- [ ] **Step 3: Add status parameter handling to handleGetNodes**

In `AdminKnowledgeResource.java`, in `handleGetNodes()`, after the `name` filter (after line 204):

```java
            if ( request.getParameter( "status" ) != null ) {
                filters.put( "status", request.getParameter( "status" ) );
            }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -pl wikantik-rest -Dtest=AdminKnowledgeResourceTest`

Expected: All tests pass.

- [ ] **Step 5: Commit**

```bash
git add wikantik-rest/src/main/java/com/wikantik/rest/AdminKnowledgeResource.java \
       wikantik-rest/src/test/java/com/wikantik/rest/AdminKnowledgeResourceTest.java
git commit -m "feat(knowledge): add status filter parameter to node list endpoint"
```

---

### Task 4: Add status dropdown to Node Explorer frontend

**Files:**
- Modify: `wikantik-frontend/src/api/client.js:301-306`
- Modify: `wikantik-frontend/src/components/admin/GraphExplorer.jsx`

- [ ] **Step 1: Add status parameter to API client queryNodes**

In `wikantik-frontend/src/api/client.js`, update the `queryNodes` method:

```javascript
    queryNodes: ({ node_type, name, status, limit = 50, offset = 0 } = {}) => {
      const params = new URLSearchParams({ limit, offset });
      if (node_type) params.set('node_type', node_type);
      if (name) params.set('name', name);
      if (status) params.set('status', status);
      return request(`/admin/knowledge/nodes?${params}`);
    },
```

- [ ] **Step 2: Add statusFilter state to GraphExplorer**

In `wikantik-frontend/src/components/admin/GraphExplorer.jsx`, add state after `typeFilter` (after line 11):

```javascript
  const [statusFilter, setStatusFilter] = useState('');
```

- [ ] **Step 3: Add status to the filter logic**

Update the `filtered` useMemo (lines 33-40) to also filter by status:

```javascript
  const filtered = useMemo(() => {
    const q = search.toLowerCase();
    return nodes.filter(n => {
      if (typeFilter && n.node_type !== typeFilter) return false;
      if (statusFilter && n.properties?.status !== statusFilter) return false;
      if (q && !n.name.toLowerCase().includes(q)) return false;
      return true;
    });
  }, [nodes, search, typeFilter, statusFilter]);
```

- [ ] **Step 4: Add status dropdown to the filter bar**

In the filter bar JSX (after the type `<select>` closing tag at line 131), add the status dropdown:

```jsx
          <select
            value={statusFilter}
            onChange={e => setStatusFilter(e.target.value)}
            className="form-input"
            style={{ width: '180px' }}
          >
            <option value="">All statuses</option>
            {(schema?.statusValues || schema?.status_values || []).map(s => (
              <option key={s} value={s}>{s}</option>
            ))}
          </select>
```

- [ ] **Step 5: Verify build**

Run: `cd wikantik-frontend && npm run build`

Expected: Build succeeds with no errors.

- [ ] **Step 6: Commit**

```bash
git add wikantik-frontend/src/api/client.js \
       wikantik-frontend/src/components/admin/GraphExplorer.jsx
git commit -m "feat(knowledge): add status filter dropdown to Node Explorer"
```

---

### Task 5: Implement RelationshipsPlugin

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/plugin/RelationshipsPlugin.java`
- Modify: `wikantik-main/src/main/resources/ini/wikantik_module.xml`
- Test: `wikantik-main/src/test/java/com/wikantik/plugin/RelationshipsPluginTest.java`

- [ ] **Step 1: Write the failing test**

Create `wikantik-main/src/test/java/com/wikantik/plugin/RelationshipsPluginTest.java`:

```java
/*
    Licensed to the Apache Software Foundation (ASF) under one
    or more contributor license agreements.  See the NOTICE file
    distributed with this work for additional information
    regarding copyright ownership.  The ASF licenses this file
    to you under the Apache License, Version 2.0 (the
    "License"); you may not use this file except in compliance
    with the License.  You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing,
    software distributed under the License is distributed on an
    "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
    KIND, either express or implied.  See the License for the
    specific language governing permissions and limitations
    under the License.
 */
package com.wikantik.plugin;

import com.wikantik.TestEngine;
import com.wikantik.api.core.Context;
import com.wikantik.api.knowledge.KnowledgeGraphService;
import com.wikantik.api.knowledge.Provenance;
import com.wikantik.api.spi.Wiki;
import com.wikantik.knowledge.DefaultKnowledgeGraphService;
import com.wikantik.knowledge.JdbcKnowledgeRepository;
import org.junit.jupiter.api.*;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.Map;
import java.util.UUID;

import static com.wikantik.TestEngine.with;
import static org.junit.jupiter.api.Assertions.*;

class RelationshipsPluginTest {

    private static DataSource dataSource;
    private static TestEngine engine;
    private static KnowledgeGraphService service;

    @BeforeAll
    static void setUp() throws Exception {
        final org.h2.jdbcx.JdbcDataSource ds = new org.h2.jdbcx.JdbcDataSource();
        ds.setURL( "jdbc:h2:mem:relationships_plugin_test;DB_CLOSE_DELAY=-1" );
        dataSource = ds;
        try( final Connection conn = ds.getConnection() ) {
            final String ddl = new String(
                RelationshipsPluginTest.class.getResourceAsStream( "/knowledge-h2.sql" ).readAllBytes() );
            conn.createStatement().execute( ddl );
        }

        engine = TestEngine.build( with( "wikantik.cache.enable", "false" ) );
        final JdbcKnowledgeRepository repo = new JdbcKnowledgeRepository( dataSource );
        service = new DefaultKnowledgeGraphService( repo );
        engine.setManager( KnowledgeGraphService.class, service );
    }

    @AfterEach
    void cleanUp() throws Exception {
        try( final Connection conn = dataSource.getConnection() ) {
            conn.createStatement().execute( "DELETE FROM kg_edges" );
            conn.createStatement().execute( "DELETE FROM kg_nodes" );
        }
    }

    @Test
    void rendersOutboundRelationships() throws Exception {
        final var a = service.upsertNode( "GraphProjector", "article", "GraphProjector.md",
                Provenance.HUMAN_AUTHORED, Map.of() );
        final var b = service.upsertNode( "KnowledgeGraphCore", "article", "KnowledgeGraphCore.md",
                Provenance.HUMAN_AUTHORED, Map.of() );
        service.upsertEdge( a.id(), b.id(), "part-of", Provenance.HUMAN_AUTHORED, Map.of() );

        final Context ctx = Wiki.context().create( engine,
                Wiki.contents().page( engine, "GraphProjector" ) );
        final String result = engine.getManager( PluginManager.class )
                .execute( ctx, "{INSERT RelationshipsPlugin}" );

        assertTrue( result.contains( "KnowledgeGraphCore" ), "Should contain target name" );
        assertTrue( result.contains( "Part of" ), "Should contain relationship label" );
        assertTrue( result.contains( "href" ), "Should contain a link" );
    }

    @Test
    void rendersInboundRelationshipsWithInvertedLabels() throws Exception {
        final var a = service.upsertNode( "KnowledgeGraphCore", "article", "KnowledgeGraphCore.md",
                Provenance.HUMAN_AUTHORED, Map.of() );
        final var b = service.upsertNode( "GraphProjector", "article", "GraphProjector.md",
                Provenance.HUMAN_AUTHORED, Map.of() );
        service.upsertEdge( b.id(), a.id(), "part-of", Provenance.HUMAN_AUTHORED, Map.of() );

        final Context ctx = Wiki.context().create( engine,
                Wiki.contents().page( engine, "KnowledgeGraphCore" ) );
        final String result = engine.getManager( PluginManager.class )
                .execute( ctx, "{INSERT RelationshipsPlugin}" );

        assertTrue( result.contains( "GraphProjector" ), "Should contain source name" );
        assertTrue( result.contains( "Parts" ), "Should contain inverted label" );
    }

    @Test
    void returnsEmptyStringWhenNoNodeExists() throws Exception {
        final Context ctx = Wiki.context().create( engine,
                Wiki.contents().page( engine, "NonExistentPage" ) );
        final String result = engine.getManager( PluginManager.class )
                .execute( ctx, "{INSERT RelationshipsPlugin}" );
        assertEquals( "", result );
    }

    @Test
    void returnsEmptyStringWhenNoEdgesExist() throws Exception {
        service.upsertNode( "LonelyNode", "article", "LonelyNode.md",
                Provenance.HUMAN_AUTHORED, Map.of() );

        final Context ctx = Wiki.context().create( engine,
                Wiki.contents().page( engine, "LonelyNode" ) );
        final String result = engine.getManager( PluginManager.class )
                .execute( ctx, "{INSERT RelationshipsPlugin}" );
        assertEquals( "", result );
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn test -pl wikantik-main -Dtest=RelationshipsPluginTest`

Expected: Compilation failure — `RelationshipsPlugin` class doesn't exist.

- [ ] **Step 3: Implement RelationshipsPlugin**

Create `wikantik-main/src/main/java/com/wikantik/plugin/RelationshipsPlugin.java`:

```java
/*
    Licensed to the Apache Software Foundation (ASF) under one
    or more contributor license agreements.  See the NOTICE file
    distributed with this work for additional information
    regarding copyright ownership.  The ASF licenses this file
    to you under the Apache License, Version 2.0 (the
    "License"); you may not use this file except in compliance
    with the License.  You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing,
    software distributed under the License is distributed on an
    "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
    KIND, either express or implied.  See the License for the
    specific language governing permissions and limitations
    under the License.
 */
package com.wikantik.plugin;

import com.wikantik.api.core.Context;
import com.wikantik.api.exceptions.PluginException;
import com.wikantik.api.knowledge.*;
import com.wikantik.api.plugin.Plugin;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Renders a page's knowledge graph relationships as navigable links.
 * Usage in wiki markup: {@code [{Relationships}]}
 *
 * <p>Outbound edges use the relationship type as-is (e.g., "Depends on").
 * Inbound edges use inverted labels (e.g., "depends-on" becomes "Dependency of").</p>
 *
 * <p>If the page has no node in the knowledge graph or no edges, renders nothing.</p>
 */
public class RelationshipsPlugin implements Plugin {

    private static final Map< String, String > INVERTED_LABELS = Map.of(
        "depends-on", "Dependency of",
        "part-of", "Parts",
        "enables", "Enabled by",
        "supersedes", "Superseded by",
        "related", "Related"
    );

    @Override
    public String execute( final Context context, final Map< String, String > params )
            throws PluginException {
        final KnowledgeGraphService service = context.getEngine()
                .getManager( KnowledgeGraphService.class );
        if( service == null ) {
            return "";
        }

        final String pageName = context.getRealPage().getName();
        final KgNode node = service.getNodeByName( pageName );
        if( node == null ) {
            return "";
        }

        final List< KgEdge > edges = service.getEdgesForNode( node.id(), "both" );
        if( edges.isEmpty() ) {
            return "";
        }

        // Batch-resolve all referenced node IDs to names
        final Set< UUID > refIds = new HashSet<>();
        edges.forEach( e -> {
            refIds.add( e.sourceId() );
            refIds.add( e.targetId() );
        } );
        final Map< UUID, String > nameMap = service.getNodeNames( refIds );

        // Group edges: outbound by relationship type, inbound by inverted label
        final Map< String, List< String > > grouped = new LinkedHashMap<>();

        for( final KgEdge edge : edges ) {
            final boolean outbound = edge.sourceId().equals( node.id() );
            final String relType = edge.relationshipType();
            final String label;
            final String targetName;

            if( outbound ) {
                label = formatLabel( relType );
                targetName = nameMap.getOrDefault( edge.targetId(), edge.targetId().toString() );
            } else {
                label = INVERTED_LABELS.getOrDefault( relType, formatLabel( relType ) );
                targetName = nameMap.getOrDefault( edge.sourceId(), edge.sourceId().toString() );
            }

            grouped.computeIfAbsent( label, k -> new ArrayList<>() ).add( targetName );
        }

        // Render HTML
        final StringBuilder html = new StringBuilder();
        html.append( "<div class=\"relationships-plugin\">" );
        html.append( "<strong>Relationships</strong>" );

        for( final Map.Entry< String, List< String > > entry : grouped.entrySet() ) {
            html.append( "<div class=\"relationship-group\">" );
            html.append( "<em>" ).append( entry.getKey() ).append( ":</em> " );
            html.append( entry.getValue().stream()
                    .map( name -> "<a href=\"/wiki/" + name + "\">" + name + "</a>" )
                    .collect( Collectors.joining( ", " ) ) );
            html.append( "</div>" );
        }

        html.append( "</div>" );
        return html.toString();
    }

    /**
     * Converts a kebab-case relationship type to a human-readable label.
     * e.g., "depends-on" → "Depends on", "part-of" → "Part of"
     */
    static String formatLabel( final String relType ) {
        if( relType == null || relType.isEmpty() ) {
            return relType;
        }
        final String spaced = relType.replace( '-', ' ' );
        return Character.toUpperCase( spaced.charAt( 0 ) ) + spaced.substring( 1 );
    }
}
```

- [ ] **Step 4: Register plugin in wikantik_module.xml**

In `wikantik-main/src/main/resources/ini/wikantik_module.xml`, add within the `<modules>` element alongside other plugin entries:

```xml
   <plugin class="com.wikantik.plugin.RelationshipsPlugin">
      <author>Wikantik</author>
      <minVersion>1.0</minVersion>
      <alias>Relationships</alias>
   </plugin>
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `mvn test -pl wikantik-main -Dtest=RelationshipsPluginTest`

Expected: All 4 tests pass.

- [ ] **Step 6: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/plugin/RelationshipsPlugin.java \
       wikantik-main/src/test/java/com/wikantik/plugin/RelationshipsPluginTest.java \
       wikantik-main/src/main/resources/ini/wikantik_module.xml
git commit -m "feat(knowledge): add RelationshipsPlugin for in-page graph navigation"
```

---

### Task 6: Decompose KnowledgeGraphCore into sub-feature pages

**Files:**
- Create: `docs/wikantik-pages/GraphProjector.md`
- Create: `docs/wikantik-pages/KnowledgeProposals.md`
- Create: `docs/wikantik-pages/KnowledgeAdminUi.md`
- Create: `docs/wikantik-pages/ProvenanceModel.md`
- Create: `docs/wikantik-pages/FrontmatterConventions.md`
- Modify: `docs/wikantik-pages/KnowledgeGraphCore.md`

- [ ] **Step 1: Create GraphProjector.md**

Create `docs/wikantik-pages/GraphProjector.md`:

```markdown
---
summary: PageFilter that synchronizes YAML frontmatter to the knowledge graph on every page save
tags:
- development
- knowledge-graph
- synchronization
type: article
status: deployed
cluster: wikantik-development
date: '2026-04-04'
part-of:
- KnowledgeGraphCore
depends-on:
- FrontmatterConventions
---
# Graph Projector

The GraphProjector is a PageFilter registered with the WikiEngine that fires on every page save. It bridges the gap between human-authored wiki content and the knowledge graph by automatically projecting YAML frontmatter into graph nodes and edges.

## How It Works

1. **Parse** — Extracts YAML frontmatter from the saved page via FrontmatterParser
2. **Detect** — Passes frontmatter to FrontmatterRelationshipDetector, which separates properties from relationships
3. **Upsert node** — Creates or updates the page's node in `kg_nodes` with detected properties, node type from the `type` field, and `human-authored` provenance
4. **Upsert edges** — For each detected relationship, resolves the target node by name (creating a stub if it doesn't exist) and upserts the edge
5. **Diff and remove** — Compares current edges against what was just projected and removes stale `human-authored` edges that no longer appear in frontmatter
6. **Preserve AI edges** — Stale-edge removal skips `ai-inferred` and `ai-reviewed` edges so approved proposals survive frontmatter edits

## Idempotency

Re-saving a page with unchanged frontmatter is a no-op — the upsert logic uses `ON CONFLICT` (PostgreSQL) or `MERGE INTO` (H2) to update timestamps without creating duplicates.

## Bulk Projection

The admin panel provides a "Project All Pages" button that iterates every wiki page and runs the projector. This is used to seed the graph after initial deployment or schema changes.

[{Relationships}]
```

- [ ] **Step 2: Create KnowledgeProposals.md**

Create `docs/wikantik-pages/KnowledgeProposals.md`:

```markdown
---
summary: Proposal workflow for AI-suggested knowledge enrichment with human approval and frontmatter write-back
tags:
- development
- knowledge-graph
- ai
- workflow
type: article
status: deployed
cluster: wikantik-development
date: '2026-04-04'
part-of:
- KnowledgeGraphCore
depends-on:
- ProvenanceModel
---
# Knowledge Proposals

The proposal system allows AI agents to suggest new nodes, edges, and property changes without directly modifying the knowledge graph. All proposals go through human review before becoming trusted knowledge.

## Proposal Types

- **new-node** — Suggest a new entity for the graph
- **new-edge** — Suggest a relationship between two existing or new nodes
- **new-property** — Suggest adding or modifying a node property
- **modify-property** — Suggest changing an existing property value

## Lifecycle

1. **Submit** — An AI agent submits a proposal via the MCP `propose_knowledge` tool or the REST API, including confidence score and reasoning
2. **Pending** — The proposal appears in the admin panel's Proposals tab for review
3. **Approve** — A human reviewer approves the proposal, which triggers edge/node creation in the graph with `ai-reviewed` provenance
4. **Reject** — A human reviewer rejects the proposal with a reason, which creates a rejection record preventing the same proposal from being resubmitted

## Frontmatter Write-Back

When a `new-edge` proposal with a `source_page` is approved, the system automatically updates the source page's YAML frontmatter to include the new relationship. For example, approving an edge from `WikantikDevelopment` to `ArtificialIntelligence` with relationship type `related` adds `ArtificialIntelligence` to the `related:` list in `WikantikDevelopment.md`. The subsequent page save triggers the GraphProjector, which recognizes the edge already exists at `ai-reviewed` provenance and avoids duplication.

## Rejection Memory

The `kg_rejections` table stores negative knowledge — relationships that were explicitly rejected. AI agents can query rejections via the MCP `list_rejections` tool to avoid re-proposing relationships that have already been evaluated and declined.

[{Relationships}]
```

- [ ] **Step 3: Create KnowledgeAdminUi.md**

Create `docs/wikantik-pages/KnowledgeAdminUi.md`:

```markdown
---
summary: Three-tab admin panel for reviewing proposals, browsing nodes, and exploring edges
tags:
- development
- knowledge-graph
- admin
- ui
- react
type: article
status: deployed
cluster: wikantik-development
date: '2026-04-04'
part-of:
- KnowledgeGraphCore
depends-on:
- AdminSecurityUi
- KnowledgeProposals
---
# Knowledge Admin UI

The knowledge admin panel is accessible at `/admin/knowledge` and provides three views for managing the knowledge graph.

## Proposals Tab

A review queue showing pending AI proposals with confidence scores and reasoning. Reviewers can approve or reject each proposal. Approvals trigger node/edge creation and frontmatter write-back. Rejections record negative knowledge.

## Node Explorer

A browsable list of all graph nodes with filters for node type, status, and text search. Selecting a node shows its properties, provenance, and all inbound/outbound edges with resolved names. Edge targets are clickable for graph navigation.

## Edge Explorer

A dedicated view for browsing all edges in the graph. Supports filtering by relationship type, searching by source or target name, and pagination. Each edge shows source name, relationship type, target name, and provenance.

## Access Control

The admin panel is protected by `AdminAuthFilter` which requires `AllPermission`. All REST endpoints under `/admin/knowledge/*` enforce the same restriction.

[{Relationships}]
```

- [ ] **Step 4: Create ProvenanceModel.md**

Create `docs/wikantik-pages/ProvenanceModel.md`:

```markdown
---
summary: Three-tier trust model tracking the origin and review status of knowledge graph content
tags:
- development
- knowledge-graph
- provenance
- trust
type: article
status: deployed
cluster: wikantik-development
date: '2026-04-04'
part-of:
- KnowledgeGraphCore
---
# Provenance Model

Every node and edge in the knowledge graph carries a provenance value that tracks its origin and trust level. This three-tier model ensures that AI-generated content is clearly distinguished from human-authored content.

## Provenance Tiers

| Tier | Value | Meaning |
|------|-------|---------|
| 1 | `human-authored` | Originated in page YAML frontmatter, written by a human |
| 2 | `ai-inferred` | Proposed by an AI agent, awaiting human review |
| 3 | `ai-reviewed` | Proposed by AI, approved by a human, written back to frontmatter |

## Upgrade Path

Content moves through provenance tiers in one direction:
- `ai-inferred` → `ai-reviewed` (on proposal approval)
- `human-authored` is the terminal state for content created directly in frontmatter

## Consumption Filtering

The read-only consumption MCP endpoint (`/knowledge-mcp`) defaults to showing only `human-authored` and `ai-reviewed` content. Agents must explicitly opt in to see `ai-inferred` (speculative) content. This ensures agents querying the graph for grounded knowledge are not misled by unreviewed proposals.

## Write-Back Semantics

When an `ai-inferred` proposal is approved, it becomes `ai-reviewed`. The frontmatter write-back adds the relationship to the source page. If the source page is re-saved, the GraphProjector recognizes the existing `ai-reviewed` edge and does not downgrade it to `human-authored`.

[{Relationships}]
```

- [ ] **Step 5: Create FrontmatterConventions.md**

Create `docs/wikantik-pages/FrontmatterConventions.md`:

```markdown
---
summary: Rules governing how YAML frontmatter keys are interpreted as graph properties, edges, and metadata
tags:
- development
- knowledge-graph
- frontmatter
- conventions
type: article
status: deployed
cluster: wikantik-development
date: '2026-04-04'
part-of:
- KnowledgeGraphCore
---
# Frontmatter Conventions

The FrontmatterRelationshipDetector determines whether each YAML frontmatter key becomes a node property or a graph edge. This convention-based approach requires no configuration — just follow the rules below.

## Detection Rules

A frontmatter key becomes a **graph edge** if:
1. Its value is a list of strings (e.g., `related: [PageA, PageB]`)
2. The key is NOT in the property-only exclusion list

Everything else becomes a **node property** stored in the JSONB `properties` column.

## Property-Only Keys

These keys are always treated as properties, never edges, even if their values are lists:

`tags`, `keywords`, `type`, `summary`, `date`, `author`, `cluster`, `status`, `title`, `description`, `category`, `language`

## Relationship Types

Any key not in the exclusion list that has a list value becomes an edge. Current relationship types in use:

| Key | Meaning | Example |
|-----|---------|---------|
| `related` | General association | `related: [McpIntegration, About]` |
| `depends-on` | Requires this to function | `depends-on: [DatabaseBackedPermissions]` |
| `part-of` | Sub-feature relationship | `part-of: [KnowledgeGraphCore]` |
| `enables` | Unlocks a capability | `enables: [AdminSecurityUi]` |
| `supersedes` | Replaces a predecessor | `supersedes: [JspToReactMigration]` |

## Status Vocabulary

The `status` field tracks development lifecycle:

| Status | Meaning |
|--------|---------|
| `idea` | Concept only, no spec |
| `designed` | Has a design spec |
| `planned` | Has spec + implementation plan |
| `active` | Currently being built |
| `deployed` | Running in the system |

## Node Type

The `type` frontmatter field maps to the node's `node_type` column. Common values: `hub`, `article`.

[{Relationships}]
```

- [ ] **Step 6: Update KnowledgeGraphCore.md as hub page**

Replace the content of `docs/wikantik-pages/KnowledgeGraphCore.md` with:

```markdown
---
summary: Property graph over wiki content with PostgreSQL storage, frontmatter synchronization,
  MCP tools, and proposal-based AI enrichment
tags:
- development
- knowledge-graph
- postgresql
- mcp
- ai
type: article
status: deployed
cluster: wikantik-development
date: '2026-04-04'
related:
- WikantikDevelopment
- McpIntegration
- DatabaseBackedPermissions
- About
- ArtificialIntelligence
- GraphProjector
- KnowledgeProposals
- KnowledgeAdminUi
- ProvenanceModel
- FrontmatterConventions
depends-on:
- McpIntegration
documents:
- AiAugmentedWorkflows
---
# Knowledge Graph Core

The knowledge graph is a semantic layer over wiki content that enables AI agents to discover relationships, traverse connections, and propose new knowledge — all grounded in human-authored content.

## Architecture

Four PostgreSQL tables form the graph:

| Table | Purpose |
|-------|---------|
| `kg_nodes` | Entities (wiki pages, concepts, stubs) with JSONB properties |
| `kg_edges` | Typed relationships between nodes with provenance tracking |
| `kg_proposals` | Pending AI-suggested enrichments awaiting human review |
| `kg_rejections` | Negative knowledge preventing re-proposals |

## Sub-Features

- **[GraphProjector](GraphProjector)** — PageFilter that synchronizes frontmatter to the graph on every page save
- **[Knowledge Proposals](KnowledgeProposals)** — Proposal/approval/rejection workflow with frontmatter write-back
- **[Knowledge Admin UI](KnowledgeAdminUi)** — Three-tab admin panel for proposals, node explorer, and edge explorer
- **[Provenance Model](ProvenanceModel)** — Three-tier trust model (human-authored → ai-inferred → ai-reviewed)
- **[Frontmatter Conventions](FrontmatterConventions)** — Rules for how frontmatter keys become properties vs. edges

## MCP Integration

Two separate MCP endpoints serve different use cases:
- `/mcp` (authoring) — 3 knowledge tools: `propose_knowledge`, `list_proposals`, `list_rejections`
- `/knowledge-mcp` (consumption) — 5 read-only tools: `discover_schema`, `query_nodes`, `traverse`, `get_node`, `search_knowledge`

[{Relationships}]
```

- [ ] **Step 7: Commit**

```bash
git add docs/wikantik-pages/GraphProjector.md \
       docs/wikantik-pages/KnowledgeProposals.md \
       docs/wikantik-pages/KnowledgeAdminUi.md \
       docs/wikantik-pages/ProvenanceModel.md \
       docs/wikantik-pages/FrontmatterConventions.md \
       docs/wikantik-pages/KnowledgeGraphCore.md
git commit -m "docs(knowledge): decompose KnowledgeGraphCore into 5 focused sub-feature pages"
```

---

### Task 7: Update existing cluster pages

**Files:**
- Modify: `docs/wikantik-pages/WikantikDevelopment.md`
- Modify: `docs/wikantik-pages/JspToReactMigration.md`
- Modify: `docs/wikantik-pages/DatabaseBackedPermissions.md`
- Modify: `docs/wikantik-pages/AdminSecurityUi.md`
- Modify: `docs/wikantik-pages/McpIntegration.md`
- Modify: `docs/wikantik-pages/BlogFeature.md`
- Modify: `docs/wikantik-pages/AttachmentManagement.md`
- Modify: `docs/wikantik-pages/KnowledgeGraphDogfooding.md`

- [ ] **Step 1: Update WikantikDevelopment.md**

Change `status: active` to `status: deployed`. Add `[{Relationships}]` at the end. Add the new feature pages to the `related` list:

Frontmatter changes:
```yaml
status: deployed
related:
- About
- JspToReactMigration
- DatabaseBackedPermissions
- KnowledgeGraphCore
- BlogFeature
- AttachmentManagement
- McpIntegration
- AdminSecurityUi
- KnowledgeGraphDogfooding
- TestStubConversion
- ConstructorInjection
- McpIntegrationTestFix
- UserProfileBio
- WikiToMarkdownConverter
- RemoveJspAndAppPrefix
- WikiAuditSkill
- McpAuditTools
- BlogEditorSplitView
```

Add at the end of the file:
```markdown

[{Relationships}]
```

- [ ] **Step 2: Update JspToReactMigration.md**

Change `status: active` to `status: deployed`. Add `enables` and `[{Relationships}]`:

Add to frontmatter:
```yaml
status: deployed
enables:
- AdminSecurityUi
- KnowledgeAdminUi
- BlogFeature
```

Add at end:
```markdown

[{Relationships}]
```

- [ ] **Step 3: Update DatabaseBackedPermissions.md**

Change `status: active` to `status: deployed`. Add `enables` and `[{Relationships}]`:

Add to frontmatter:
```yaml
status: deployed
enables:
- AdminSecurityUi
```

Add at end:
```markdown

[{Relationships}]
```

- [ ] **Step 4: Update AdminSecurityUi.md**

Change `status: active` to `status: deployed`. Add `enables` and `[{Relationships}]`:

Add to frontmatter:
```yaml
status: deployed
enables:
- KnowledgeAdminUi
```

Add at end:
```markdown

[{Relationships}]
```

- [ ] **Step 5: Update McpIntegration.md**

Change `status: active` to `status: deployed`. Add `depends-on` and `[{Relationships}]`:

Add to frontmatter:
```yaml
status: deployed
depends-on:
- KnowledgeGraphCore
```

Add at end:
```markdown

[{Relationships}]
```

- [ ] **Step 6: Update BlogFeature.md**

Change `status: active` to `status: deployed`. Add `depends-on` and `[{Relationships}]`:

Add to frontmatter:
```yaml
status: deployed
depends-on:
- JspToReactMigration
```

Add at end:
```markdown

[{Relationships}]
```

- [ ] **Step 7: Update AttachmentManagement.md**

Change `status: active` to `status: deployed`. Add `depends-on` and `[{Relationships}]`:

Add to frontmatter:
```yaml
status: deployed
depends-on:
- JspToReactMigration
```

Add at end:
```markdown

[{Relationships}]
```

- [ ] **Step 8: Update KnowledgeGraphDogfooding.md**

Add `[{Relationships}]` at the end of the file (status is already `active`, which is correct).

- [ ] **Step 9: Commit**

```bash
git add docs/wikantik-pages/WikantikDevelopment.md \
       docs/wikantik-pages/JspToReactMigration.md \
       docs/wikantik-pages/DatabaseBackedPermissions.md \
       docs/wikantik-pages/AdminSecurityUi.md \
       docs/wikantik-pages/McpIntegration.md \
       docs/wikantik-pages/BlogFeature.md \
       docs/wikantik-pages/AttachmentManagement.md \
       docs/wikantik-pages/KnowledgeGraphDogfooding.md
git commit -m "docs(knowledge): update cluster pages with deployed status and richer relationships"
```

---

### Task 8: Backfill missing feature wiki pages

**Files:**
- Create: `docs/wikantik-pages/BlogEditorSplitView.md`
- Create: `docs/wikantik-pages/TestStubConversion.md`
- Create: `docs/wikantik-pages/ConstructorInjection.md`
- Create: `docs/wikantik-pages/McpIntegrationTestFix.md`
- Create: `docs/wikantik-pages/UserProfileBio.md`
- Create: `docs/wikantik-pages/WikiToMarkdownConverter.md`
- Create: `docs/wikantik-pages/RemoveJspAndAppPrefix.md`
- Create: `docs/wikantik-pages/WikiAuditSkill.md`
- Create: `docs/wikantik-pages/McpAuditTools.md`

- [ ] **Step 1: Create BlogEditorSplitView.md**

```markdown
---
summary: Live preview split-view Markdown editor for blog entry creation
tags:
- development
- blog
- editor
- ui
type: article
status: deployed
cluster: wikantik-development
date: '2026-04-03'
part-of:
- BlogFeature
related:
- JspToReactMigration
---
# Blog Editor Split View

The blog editor provides a split-view Markdown editing experience for blog entry creation. The left panel is a Markdown text editor and the right panel shows a live-rendered preview that updates as you type.

## Features

- Real-time Markdown preview using the wiki's rendering pipeline
- Frontmatter editing for blog metadata (synopsis, author, date)
- Date-prefixed filename generation following the `YYYYMMDD-Title` convention
- Integration with the blog plugin system for immediate publishing

[{Relationships}]
```

- [ ] **Step 2: Create TestStubConversion.md**

```markdown
---
summary: Lightweight test stubs replacing TestEngine for decoupled, fast unit tests
tags:
- development
- testing
- stubs
- test-infrastructure
type: article
status: deployed
cluster: wikantik-development
date: '2026-03-23'
related:
- ConstructorInjection
- WikantikDevelopment
---
# Test Stub Conversion

The test stub conversion replaced heavy TestEngine-based unit tests with lightweight stub implementations. Three key stubs were introduced: StubPageManager, StubSystemPageRegistry, and StubReferenceManager.

## Motivation

Many unit tests only needed one or two manager methods but were forced to spin up a full TestEngine with file-based providers, search indexes, and security configuration. This made tests slow and brittle.

## Stubs

- **StubPageManager** — In-memory page storage with put/get semantics
- **StubSystemPageRegistry** — Returns false for all system page checks
- **StubReferenceManager** — No-op reference tracking

## Impact

Test classes using stubs run significantly faster and have no filesystem or configuration dependencies.

[{Relationships}]
```

- [ ] **Step 3: Create ConstructorInjection.md**

```markdown
---
summary: Testability refactor from service locator pattern to constructor dependency injection
tags:
- development
- testing
- refactoring
- dependency-injection
type: article
status: deployed
cluster: wikantik-development
date: '2026-03-29'
related:
- TestStubConversion
- WikantikDevelopment
---
# Constructor Injection

The constructor injection initiative refactored high-value classes from the service locator pattern (calling `engine.getManager()` internally) to accepting dependencies via constructor parameters.

## Motivation

The service locator pattern made unit testing difficult — tests had to create a full WikiEngine even when only one manager was needed. Constructor injection allows tests to pass lightweight stubs directly.

## Classes Refactored

Seven high-value classes were converted, including core rendering and reference management components. Each class gained a constructor accepting its dependencies while maintaining backwards compatibility through a default constructor that falls back to service location.

[{Relationships}]
```

- [ ] **Step 4: Create McpIntegrationTestFix.md**

```markdown
---
summary: Fixed WikiEngine lazy initialization and failsafe reporting for MCP integration tests
tags:
- development
- testing
- mcp
- integration-tests
type: article
status: deployed
cluster: wikantik-development
date: '2026-03-12'
related:
- McpIntegration
- WikantikDevelopment
---
# MCP Integration Test Fix

The MCP integration tests were silently failing due to two root causes: WikiEngine lazy initialization during test setup, and Maven Failsafe's `testFailureIgnore` setting suppressing failures.

## Root Causes

1. **Lazy initialization** — The WikiEngine was not fully initialized when MCP tools tried to access managers during integration tests, causing null pointer exceptions
2. **Silent failures** — The `testFailureIgnore` configuration in the Failsafe plugin meant test failures were logged but did not break the build

## Fix

- Ensured WikiEngine is fully initialized before MCP tool registration
- Removed `testFailureIgnore` so integration test failures properly fail the build
- Added explicit failsafe reporting to surface test results

[{Relationships}]
```

- [ ] **Step 5: Create UserProfileBio.md**

```markdown
---
summary: Bio field on user profiles for personal descriptions
tags:
- development
- user-profile
- ui
type: article
status: deployed
cluster: wikantik-development
date: '2026-04-03'
related:
- WikantikDevelopment
---
# User Profile Bio

The user profile bio feature adds a free-text biography field to user profiles. Users can write a short description of themselves that is displayed on their profile page.

## Implementation

The bio field is stored in the `users` table and editable through the user preferences page in the React SPA. The field supports plain text with a reasonable character limit.

[{Relationships}]
```

- [ ] **Step 6: Create WikiToMarkdownConverter.md**

```markdown
---
summary: Migration tool converting legacy JSPWiki syntax pages to Markdown format
tags:
- development
- migration
- markdown
- converter
type: article
status: deployed
cluster: wikantik-development
date: '2026-04-01'
enables:
- BlogFeature
- KnowledgeGraphCore
related:
- WikantikDevelopment
---
# Wiki-to-Markdown Converter

The wiki-to-markdown converter migrated legacy JSPWiki syntax pages to Markdown format. This was a prerequisite for the knowledge graph (which parses YAML frontmatter from Markdown pages) and the blog feature (which uses Markdown rendering).

## Conversion Rules

- JSPWiki headings (`!!!`, `!!`, `!`) converted to Markdown headings (`#`, `##`, `###`)
- Wiki links (`[PageName]`) converted to Markdown links
- Inline formatting (bold, italic) converted to Markdown equivalents
- Plugin syntax preserved as-is (Flexmark handles plugin rendering)

## Frontmatter Addition

During conversion, YAML frontmatter was added to pages that lacked it, with basic metadata fields (summary, tags, type, status).

[{Relationships}]
```

- [ ] **Step 7: Create RemoveJspAndAppPrefix.md**

```markdown
---
summary: Final removal of JSP templates and the /app/ URL prefix for a single SPA entry point
tags:
- development
- migration
- jsp
- routing
type: article
status: deployed
cluster: wikantik-development
date: '2026-03-29'
depends-on:
- JspToReactMigration
supersedes:
- JspToReactMigration
related:
- WikantikDevelopment
---
# Remove JSP and /app/ Prefix

This cleanup step completed the JSP-to-React migration by removing all remaining JSP templates and consolidating the URL structure. Previously, the React SPA was served under `/app/` while legacy JSP pages remained at the root. After full feature parity was achieved, all JSP templates were deleted and the SPA was promoted to serve from the root URL.

## Changes

- Removed all JSP template files from the WAR
- Updated SpaRoutingFilter to serve the React SPA from `/` instead of `/app/`
- Redirected legacy `/app/` URLs to root for backwards compatibility
- Cleaned up web.xml servlet and filter mappings for removed JSP servlets

[{Relationships}]
```

- [ ] **Step 8: Create WikiAuditSkill.md**

```markdown
---
summary: Designed but not yet built skill for automated wiki content quality auditing
tags:
- development
- audit
- content-quality
- mcp
type: article
status: designed
cluster: wikantik-development
date: '2026-03-20'
depends-on:
- McpIntegration
related:
- McpAuditTools
- WikantikDevelopment
---
# Wiki Audit Skill

The wiki audit skill is a designed but not yet implemented capability for automated content quality auditing. An AI agent would use MCP audit tools to scan wiki pages for quality issues — broken links, missing frontmatter, orphaned pages, inconsistent formatting — and generate reports or proposals for fixes.

## Design Status

A design spec exists at `docs/superpowers/specs/2026-03-20-wiki-audit-skill-design.md`. No implementation plan has been created yet.

## Intended Capabilities

- Scan pages for broken internal links and missing references
- Identify pages missing required frontmatter fields
- Find orphaned pages with no inbound links
- Report formatting inconsistencies
- Propose frontmatter corrections via the knowledge proposal system

[{Relationships}]
```

- [ ] **Step 9: Create McpAuditTools.md**

```markdown
---
summary: Designed MCP tools that would power automated content auditing
tags:
- development
- mcp
- audit
- tools
type: article
status: designed
cluster: wikantik-development
date: '2026-03-20'
part-of:
- McpIntegration
enables:
- WikiAuditSkill
related:
- WikantikDevelopment
---
# MCP Audit Tools

The MCP audit tools are a designed but not yet built set of MCP tools that would power the wiki audit skill. These tools would provide structured access to content quality metrics and validation results.

## Design Status

A design spec exists at `docs/superpowers/specs/2026-03-20-mcp-audit-tools-design.md`. The tools would be added to the existing authoring MCP server at `/mcp`.

## Planned Tools

- **scan_broken_links** — Find pages with links to non-existent pages
- **scan_missing_frontmatter** — Find pages missing required metadata fields
- **scan_orphaned_pages** — Find pages with no inbound references
- **validate_frontmatter** — Check frontmatter against schema conventions
- **audit_cluster** — Comprehensive quality report for a page cluster

[{Relationships}]
```

- [ ] **Step 10: Commit**

```bash
git add docs/wikantik-pages/BlogEditorSplitView.md \
       docs/wikantik-pages/TestStubConversion.md \
       docs/wikantik-pages/ConstructorInjection.md \
       docs/wikantik-pages/McpIntegrationTestFix.md \
       docs/wikantik-pages/UserProfileBio.md \
       docs/wikantik-pages/WikiToMarkdownConverter.md \
       docs/wikantik-pages/RemoveJspAndAppPrefix.md \
       docs/wikantik-pages/WikiAuditSkill.md \
       docs/wikantik-pages/McpAuditTools.md
git commit -m "docs(knowledge): backfill 9 wiki pages for features with specs/plans but no wiki representation"
```

---

### Task 9: Build and verify

- [ ] **Step 1: Run full build**

```bash
mvn clean install -T 1C -DskipITs
```

Expected: BUILD SUCCESS. All unit tests pass.

- [ ] **Step 2: Redeploy to local Tomcat**

```bash
tomcat/tomcat-11/bin/shutdown.sh
rm -rf tomcat/tomcat-11/webapps/ROOT
cp wikantik-war/target/Wikantik.war tomcat/tomcat-11/webapps/ROOT.war
tomcat/tomcat-11/bin/startup.sh
```

- [ ] **Step 3: Verify via admin panel**

Open `http://localhost:8080/admin/knowledge`:

1. **Node Explorer** — Verify the status dropdown appears alongside the type dropdown. Select "deployed" — should filter to only deployed nodes.
2. **Edge Explorer** — Verify new relationship types (`part-of`, `enables`, `supersedes`) appear in the relationship type filter.
3. **Project All Pages** — Click to project all new/updated wiki pages. Node and edge counts should increase.

- [ ] **Step 4: Verify RelationshipsPlugin renders on pages**

Navigate to `http://localhost:8080/wiki/GraphProjector`. The `[{Relationships}]` plugin at the bottom should render:

```
Relationships
Part of: KnowledgeGraphCore
Depends on: FrontmatterConventions
```

Each name should be a clickable link.

- [ ] **Step 5: Final commit if any fixups needed**

If any issues are found during verification, fix and commit.
