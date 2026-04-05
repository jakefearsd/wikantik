# Database Updates

## Knowledge Graph Schema (v1.0.0)

The knowledge graph feature requires four new tables: `kg_nodes`, `kg_edges`, `kg_proposals`, and `kg_rejections`.

### Migration Script

Run the DDL script after the base `postgresql.ddl` has been applied:

```bash
sudo -u postgres psql -d wikantik -f wikantik-war/src/main/config/db/postgresql-knowledge.ddl
```

The script is idempotent (`CREATE TABLE IF NOT EXISTS`, `CREATE INDEX IF NOT EXISTS`) and safe to re-run.

### Index Notes

The `idx_kg_nodes_properties` GIN index on `kg_nodes.properties` is currently **unused**. The `searchNodes` query casts properties to text (`properties::text`) and uses `LIKE`, which bypasses the GIN index entirely. To make use of it, the query would need to use JSONB containment operators (`@>`) instead. Until that change is made, the index adds write overhead with no read benefit.

The `idx_kg_edges_type` and `idx_kg_edges_provenance` indexes on `kg_edges` are also unused by current queries. No code path filters edges by relationship type or provenance in isolation. These may become useful as the query surface grows, but could be dropped if write performance on edges becomes a concern.
