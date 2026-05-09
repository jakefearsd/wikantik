# Wikantik Operations Handbook

This document is the definitive handbook and runbook for Wikantik Administration. It is designed to serve both new and experienced administrators in managing system configuration, deployment strategies, script execution, and knowledge graph operations.

---

## 1. System Configuration & Deployment

Wikantik supports two primary deployment strategies: a bare-metal Tomcat 11 installation and a fully containerized Docker architecture. 

### 1.1 Configuration via `.env`
Configuration for both bare-metal and container environments relies heavily on environment variables, typically managed via a `.env` file at the project root. Key variables include:
- `POSTGRES_PASSWORD`: The database access password.
- `POSTGRES_USER` & `POSTGRES_DB`: (Defaults to `wikantik`).
- `MCP_ACCESS_KEYS`: Security tokens required for accessing MCP endpoints.
- `MAIL_SMTP_PASSWORD`: Credentials for email dispatch.

### 1.2 Containerized Deployment (Recommended for Production)
The production Docker environment runs three critical services defined in `docker-compose.yml` and `docker-compose.prod.yml`:
1. `wikantik`: The primary application running on Tomcat 11/JDK 21.
2. `db`: PostgreSQL 17 database.
3. `backup`: An Alpine-based container that executes cron jobs for scheduled backups.

**Persistent Volumes:**
- `pgdata`: Holds PostgreSQL user, group, and role data.
- `wikantik-pages`: Contains the actual wiki content (`.md`, `.properties`, and attachments).
- `wikantik-work` & `wikantik-logs`: Hold the rebuilt Lucene index and logs (these are not critical for backups as they reconstruct automatically).

**CI/CD & Rollback:**
Deployments follow a "push-on-green" methodology. Upon failure of a health check (`curl http://localhost:8080/wiki/Main`), the CI pipeline automatically tags and loads the previous commit SHA image to achieve zero-downtime rollback. 

### 1.3 Bare-Metal Deployment
Deploying to a bare-metal server involves unpacking Tomcat locally and deploying the `.war`. This process is automated using the `bin/deploy-local.sh` script, which configures context templates and manages JDBC drivers.

---

## 2. Backup & Disaster Recovery

Wikantik utilizes a three-tier retention backup strategy targeting PostgreSQL dumps and page archives.

- **Schedule:** Daily (30 days), Weekly (12 weeks), and Monthly (12 months).
- **Artifacts:**
  - `db.sql`: Database dump containing users, roles, and group memberships.
  - `pages.tar.gz`: The core content stored in `/var/wikantik/pages/`.
- **Restoration:** Handled manually via the `backup` sidecar container (e.g., `bin/container.sh restore /backups/daily/2026-03-23`). The `wikantik` application container should be stopped during restoration to prevent concurrent write corruption.

---

## 3. Administrative Scripts (`bin/`)

The `bin/` directory contains operational scripts to manage the Wikantik lifecycle and Knowledge Graph (KG).

### `bin/container.sh`
The primary wrapper around `docker compose` for the container stack.
- `build`, `up -d`, `down`: Standard stack orchestration.
- `backup [TIER]`: Triggers an ad-hoc backup inside the prod sidecar.
- `restore PATH`: Restores DB and content from a snapshot path.
- `psql`: Opens an interactive PostgreSQL shell in the DB container.
- `smoke-test`: Spins up `docker-compose.test.yml` to verify health checks before tear down.

### `bin/deploy-local.sh`
Handles bare-metal Tomcat deployments.
- Renders `context.xml` and properties templates using `.env`.
- `--upgrade-tomcat`: Performs an in-place upgrade, preserving managed configs and data directories safely.

### `bin/kg-rebuild.sh`
Orchestrates the full content and Knowledge Graph rebuild pipeline across multiple phases.
- Phase 1: Rebuild chunks and Lucene index.
- Phase 2: Reindex embeddings.
- Phase 3: Optional reset (`--reset-kg`) to prune AI-inferred states or a complete destructive wipe (`--purge-kg`).
- Phase 4: Forward requests to `bin/kg-extract.sh` to extract mentions and proposals.

### `bin/kg-extract.sh`
Fires the standalone entity-extractor CLI against the database to generate Knowledge Graph nodes and proposals.
- Supports tuning parameters like `--max-pages`, `--ollama-model`, and `--concurrency`.
- Recompiles the `wikantik-extract-cli.jar` transparently if Java source files have changed.

### `bin/kg-policy.sh`
Admin CLI for managing the Knowledge Graph cluster inclusion/exclusion policies.
- Controls what namespaces are analyzed by the extractor (system pages are automatically excluded).
- Commands include `list`, `set`, `explain`, and `purge`.

### `bin/kg-judge.sh`
Triggers ad-hoc Knowledge Graph judge runs against the local deployment.
- `--proposal-id UUID`: Synchronously judge one proposal.
- `--status`: Evaluate pending queue depth.

---

## 4. Knowledge Graph Administration

The Knowledge Graph captures structured entities (nodes) and their relationships (edges). Administered primarily via `/admin/knowledge/` in the UI and supported by Model Context Protocol (MCP) integrations.

### 4.1 Provenance & Graph Projector
Nodes and edges carry a `provenance` label:
- `human-authored`: Extracted from YAML frontmatter (e.g., `related: [PageName]`) and body links.
- `ai-inferred`: Suggested by AI but pending admin approval.
- `ai-reviewed`: AI proposals validated by an administrator.

The **Graph Projector** runs on every page save. It parses frontmatter to insert relationship edges and removes stale data. Missing target pages are created as "stub nodes".

### 4.2 Proposals & AI Integration
AI agents connected via MCP submit `new-node`, `new-edge`, or `modify-property` proposals through the `propose_knowledge` tool. 
- **Approval:** A newly accepted `new-edge` relation is written back to the source page's YAML frontmatter.
- **Rejection:** Rejections include reasons and prevent agents from submitting the exact same proposal again.

### 4.3 Node & Edge Curation
- **Stub Nodes:** Frequent review of stub nodes is encouraged. They represent missing pages or potential typos in YAML blocks.
- **Edge Types:** Standard conventions (e.g., `related`, `depends_on`, `implements`, `supersedes`) should be maintained across the wiki for querying clarity.

### 4.4 Embeddings & Advanced Quality Tools
Wikantik leverages a unified embedding model for hybrid search and structural similarity.
- **Merge Candidates:** Finds structurally and semantically similar nodes. Merging updates all corresponding edges and frontmatter references.
- **Missing Edges:** The system predicts missing relationships based on topology.
- **Low-Plausibility Edges:** Flags existing connections that seem structurally anomalous. 
- **Pages Without Frontmatter:** Accessible under Content Embeddings, used to flag pages that have zero footprint in the semantic graph.