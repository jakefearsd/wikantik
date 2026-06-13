# Bundle corpus — gold review worksheet

Generated from `eval/bundle-corpus/queries.csv` by `bin/eval/build-review-worksheet.py`. For each question, confirm the cited section(s) actually answer the query. Mark each: **OK**, **DROP**, or **FIX → <page>#<heading>**. RELATIONAL = the golds must answer it *together* (genuinely multi-hop). BOUNDARY = the answer must *straddle* the two sections.


---

## q01 — SIMILARITY

**Query:** how do I deploy the wiki locally


**Gold 1:** `BuildingAndDeployingLocally.md` → `Walkthrough`

> **Walkthrough**
>
> The frontmatter `steps` are the canonical sequence. The readiness
> check at the end (step 6) is the difference between "Tomcat is up"
> and "the structural index has finished bootstrap" — both matter for
> agent-grade endpoints.


- [ ] verdict: ______  (OK / DROP / FIX → ____)


---

## q02 — SIMILARITY

**Query:** which embedding model should we pick for local RAG


**Gold 1:** `LocalRAG.md` → `Component choices>Embedding model`

> **Embedding model**
>
> Options (all local-friendly):
> 
> - **BGE-small / BGE-base / BGE-large** (BAAI) — strong open embedding family. BGE-large is competitive with commercial.
> - **e5-base / e5-large** (Microsoft) — strong; well-supported.
> - **gte-large / gte-multilingual** — solid, multilingual variants.
> - **Nomic-embed-v1.5 / v2** — Apache 2.0; long-context.
> - **all-MiniLM-L6-v2** — small (60MB); fast; weaker but fine for small corpora.
> - **Jina v3** — multilingual, multi-modal capable.
> 
> For most use cases: BGE-base or BGE-large is the safe default. Run via `sentence-transformers` library. CPU-friendly.


- [ ] verdict: ______  (OK / DROP / FIX → ____)


---

## q03 — SIMILARITY

**Query:** Ollama installation


**Gold 1:** `OllamaSetup.md` → `Hardware Sizing: vRAM Requirements`

> **Hardware Sizing: vRAM Requirements**
>
> The most critical factor in local inference is the available video RAM (vRAM). If a model does not fit in vRAM, it offloads to system RAM, which is significantly slower (often 10x-50x slower).
> 
> ### Llama 3 vRAM Table (Approximate)
> 
> | Model Size | Quantization | vRAM Required | Recommended Hardware |
> |---|---|---|---|
> | **Llama 3 8B** | 4-bit (Q4_K_M) | ~5.5 GB | RTX 3060 (12GB) / Apple M1+ |
> | **Llama 3 8B** | 8-bit (Q8_0) | ~9.0 GB | RTX 3080 (10GB+) / Apple M1+ |
> | **Llama 3 70B** | 4-bit (Q4_K_M) | ~40.0 GB | 2x RTX 3090/4090 (48GB) / A6000 |
> | **Llama 3 70B** | 8-bit (Q8_0) | ~72.0 GB | 2x A6000 / A100 / Mac Studio (128GB) |
> 
> **Note on Unified Memory:** Apple Silicon (Mac Studio/Pro) uses unified memory, meaning system RAM can be allocated to the GPU. For 70B models, a Mac with 64GB+ RAM is often the most cost-effective local solution.


- [ ] verdict: ______  (OK / DROP / FIX → ____)


---

## q04 — SIMILARITY

**Query:** running Wikantik in a container


**Gold 1:** `WikantikOnDocker.md` → `Architecture Overview`

> **Architecture Overview**
>
> The containerized deployment runs three services orchestrated by Docker Compose:
> 
> | Service | Image | Purpose |
> |---------|-------|---------|
> | **db** | `postgres:17-alpine` | Stores users, groups, and roles |
> | **wikantik** | Custom (Tomcat 11 / JDK 21) | Runs the wiki application |
> | **backup** | `postgres:17-alpine` | Automated database dumps and page file archives (production only) |
> 
> All three services share a private Docker network. The `wikantik` container connects to `db` by hostname. The `backup` container connects to `db` for `pg_dump` and mounts the same pages volume as `wikantik` to archive page files. Only port 8080 is exposed to the host.
> 
> ### What lives where
> 
> | Data | Container path | Docker volume | Can rebuild? | Backed up? |
> |------|---------------|---------------|-------------|------------|
> | Wiki pages (.md + .properties) | `/var/wikantik/pages` | `wikantik-pages` | **NO** | **YES** |
> | File attachments | `/var/wikantik/pages` | `wikantik-pages` | **NO** | **YES** |
> | PostgreSQL data | `/var/lib/postgresql/data` | `pgdata` | **NO** | **YES** |
> | Lucene search index | `/var/wikantik/work` | `wikantik-work` | YES (auto-rebuilds on startup) | No |
> | Reference mana
> … (truncated)


- [ ] verdict: ______  (OK / DROP / FIX → ____)


---

## q05 — SIMILARITY

**Query:** setting up postgres on my laptop


**Gold 1:** `PostgreSQLLocalDeployment.md` → `Fresh Deployment (Clean Instance)`

> **Fresh Deployment (Clean Instance)**
>
> ### 1. Bootstrap the database
> 
> `install-fresh.sh` creates the database, the application role, and runs every migration in order. It is idempotent — re-running against an already-bootstrapped database is a no-op.
> 
> ```bash
> sudo -u postgres \
>     DB_NAME=wikantik \
>     DB_APP_USER=jspwiki \
>     DB_APP_PASSWORD='ChangeMe123!' \
>     bin/db/install-fresh.sh
> ```
> 
> This creates:
> 
> - Database `wikantik` with the `pgvector` extension installed
> - Application role `jspwiki` with `CONNECT` + `USAGE` on `public`
> - All tables from `bin/db/migrations/V001`–`V010` (users/roles/groups, policy grants, knowledge graph, content chunks + embeddings, API keys)
> - A `schema_migrations` table so `migrate.sh` knows what has been applied
> 
> To bootstrap a dedicated migration role (recommended for production so `ALTER` migrations don't run as `postgres`), also set `DB_MIGRATE_PASSWORD` and the script will call `create-migrate-user.sh` and transfer ownership.
> 
> ### 2. Build the WAR
> 
> ```bash
> mvn clean install -Dmaven.test.skip -T 1C
> ```
> 
> This compiles all modules, builds the React frontend, and produces `wikantik-war/target/Wikantik.war`. Use a full `mvn clean install` (without `-Dmaven.test.skip`) before any product
> … (truncated)


- [ ] verdict: ______  (OK / DROP / FIX → ____)


---

## q06 — SIMILARITY

**Query:** cors preflight handling


**Gold 1:** `CorsDeepDive.md` → `How CORS works`

> **How CORS works**
>
> ### Simple requests
> 
> GET, HEAD, or POST with simple content types (form-data, text/plain) and no special headers can be sent immediately. The browser includes:
> 
> ```
> Origin: https://app.example.com
> ```
> 
> The server responds with:
> 
> ```
> Access-Control-Allow-Origin: https://app.example.com
> ```
> 
> (Or `*` for any origin, with limitations.)
> 
> If the response has the right header, the browser lets JS read it. If not, the browser blocks JS access (the request was sent and the response received, but JS can't read it).
> 
> ### Preflight requests
> 
> For requests that aren't simple (DELETE, PUT, custom headers, JSON content type), the browser sends an OPTIONS preflight request first:
> 
> ```http
> OPTIONS /api/orders
> Origin: https://app.example.com
> Access-Control-Request-Method: DELETE
> Access-Control-Request-Headers: Authorization
> ```
> 
> Server responds:
> 
> ```http
> HTTP/1.1 204 No Content
> Access-Control-Allow-Origin: https://app.example.com
> Access-Control-Allow-Methods: GET, POST, DELETE
> Access-Control-Allow-Headers: Authorization, Content-Type
> Access-Control-Max-Age: 3600
> ```
> 
> The browser then sends the actual request.
> 
> The preflight is cached per the Max-Age; subsequent requests skip it.


- [ ] verdict: ______  (OK / DROP / FIX → ____)


---

## q07 — SIMILARITY

**Query:** full text search in relational database


**Gold 1:** `FullTextSearchInPostgresql.md` → `The basics`

> **The basics**
>
> ### tsvector and tsquery
> 
> PostgreSQL stores searchable text as `tsvector` (tokenized; sorted; deduplicated):
> 
> ```sql
> SELECT to_tsvector('english', 'The quick brown fox jumps over the lazy dog');
> -- 'brown':3 'dog':9 'fox':4 'jump':5 'lazi':8 'quick':2
> ```
> 
> Stop words removed; words stemmed; positions tracked.
> 
> ### Searching
> 
> ```sql
> SELECT * FROM articles
> WHERE to_tsvector('english', body) @@ to_tsquery('english', 'fox & dog');
> ```
> 
> The `@@` operator matches a tsvector against a tsquery.
> 
> ### Generated column
> 
> For performance, store the tsvector:
> 
> ```sql
> ALTER TABLE articles
> ADD COLUMN search_vector tsvector
> GENERATED ALWAYS AS (to_tsvector('english', body)) STORED;
> ```
> 
> Now searches don't recompute the vector each time.
> 
> ### GIN index
> 
> For fast lookup:
> 
> ```sql
> CREATE INDEX articles_search_idx ON articles USING GIN (search_vector);
> ```
> 
> GIN indexes for tsvector are the standard.


- [ ] verdict: ______  (OK / DROP / FIX → ____)


---

## q08 — SIMILARITY

**Query:** local retrieval augmented generation


**Gold 1:** `LocalRAG.md` → `The minimum stack`

> **The minimum stack**
>
> Three components:
> 
> 1. **Embedding model** — locally hosted; embeds queries and document chunks.
> 2. **Vector store** — local index of chunk embeddings.
> 3. **LLM** — locally hosted; generates the answer from retrieved chunks.
> 
> Plus retrieval orchestration (the glue), chunking pipeline, optional reranker.


- [ ] verdict: ______  (OK / DROP / FIX → ____)


---

## q09 — SIMILARITY

**Query:** blue green release strategy


**Gold 1:** `BlueGreenDeployments.md` → `How it works`

> **How it works**
>
> Two production environments — "blue" and "green" — both capable of serving real traffic. Only one is live at a time.
> 
> ```
> Today:    Blue (v1.0) → live traffic
>           Green (v2.0) → idle, deployed
> 
> Switch:   Blue (v1.0) → idle  
>           Green (v2.0) → live traffic   ← cut over via load balancer
> 
> Tomorrow: Green (v2.0) → live traffic
>           Blue (v3.0) → idle, deployed
> ```
> 
> Mechanism: a load balancer or DNS swap routes traffic. The "switch" is fast (seconds to minutes); rollback is the same swap in reverse.


- [ ] verdict: ______  (OK / DROP / FIX → ____)


---

## q10 — SIMILARITY

**Query:** gradual rollout with traffic shifting


**Gold 1:** `CanaryDeployments.md` → `1. Traffic Splitting Mechanisms`

> **1. Traffic Splitting Mechanisms**
>
> The core of a canary is the ability to route a precise percentage of traffic.
> 
> *   **Layer 4 (IP-based):** Simple, but lacks granularity. Often used at the Load Balancer level (e.g., AWS Target Group weights).
> *   **Layer 7 (Request-based):** Superior for modern APIs. Routes based on headers, cookies, or user IDs.
> *   **Concrete Example (Istio):** Use a `VirtualService` to route 90% of traffic to the `stable` subset and 10% to the `canary` subset.
>     ```yaml
>     http:
>     - route:
>       - destination: {host: my-svc, subset: stable}, weight: 90
>       - destination: {host: my-svc, subset: canary}, weight: 10
>     ```


- [ ] verdict: ______  (OK / DROP / FIX → ____)


---

## q11 — SIMILARITY

**Query:** oauth authorization code flow


**Gold 1:** `OAuthImplementation.md` → `OAuth Flow Integration`

> **OAuth Flow Integration**
>
> ```


- [ ] verdict: ______  (OK / DROP / FIX → ____)


---

## q12 — SIMILARITY

**Query:** single sign on with identity providers


**Gold 1:** `FullOAuth.md` → `Part B: Architecture Overview`

> **Part B: Architecture Overview**
>
> ### B.0 Authentication Options Overview
> 
> Wikantik supports multiple authentication methods that coexist seamlessly:
> 
> ```
> ┌─────────────────────────────────────────────────────────────────────────────┐
> │                    Authentication Options for Users                          │
> └─────────────────────────────────────────────────────────────────────────────┘
> 
>                               ┌─────────────────────┐
>                               │    Login Page       │
>                               │                     │
>                               │  ┌───────────────┐  │
>                               │  │ Username: ___ │  │  ← Traditional Login
>                               │  │ Password: ___ │  │    (existing functionality)
>                               │  │   [Log In]    │  │
>                               │  └───────────────┘  │
>                               │                     │
>                               │    ── OR ──         │
>                               │                     │
>                               │  ┌───────────────┐  │
>                               │  │ Continue with │  │  ← OAuth Login
>                               │  │    Google     │  │    (new functiona
> … (truncated)


- [ ] verdict: ______  (OK / DROP / FIX → ____)


---

## q13 — SIMILARITY

**Query:** storage patterns for files


**Gold 1:** `ObjectStoragePatterns.md` → `What object storage is good for`

> **What object storage is good for**
>
> ### Large files
> 
> Videos, images, documents, backups. Files where size matters.
> 
> ### Static assets
> 
> CSS, JS, images for web apps. Combined with CDN, fastest possible serving.
> 
> ### Data lake / lakehouse
> 
> Parquet files, JSON, CSV. Queried by Athena, BigQuery, Spark.
> 
> ### Backups
> 
> Database backups, log archives, snapshots. Cheap long-term storage.
> 
> ### Object archive
> 
> Compliance retention, legal hold. Retention rules and lifecycle to cold storage.
> 
> ### Event-driven workflows
> 
> S3 upload triggers Lambda; ETL pipeline begins.


- [ ] verdict: ______  (OK / DROP / FIX → ____)


---

## q14 — SIMILARITY

**Query:** how do we protect against supply chain attacks


**Gold 1:** `SupplyChainSecurity.md` → `Defenses`

> **Defenses**
>
> ### Dependency scanning
> 
> Continuously scan dependencies for known vulnerabilities. CVE database is updated; your scanner checks.
> 
> Tools:
> - Dependabot (GitHub native)
> - Snyk
> - OWASP Dependency-Check
> - AWS Inspector
> - Trivy (containers)
> 
> Scan in CI; block deploys with critical CVEs. Triage and patch quickly.
> 
> ### Pin versions
> 
> Don't use floating versions. `1.+` or `latest` means new versions enter without review.
> 
> Pin to specific versions; review updates before bumping.
> 
> ### Lock files
> 
> `package-lock.json`, `yarn.lock`, `Pipfile.lock`, `Gemfile.lock`. Record exact versions of transitive dependencies. Reproducible builds; no surprise updates.
> 
> ### Verify package provenance
> 
> Modern package registries support cryptographic signing. Verify:
> - npm provenance (sigstore)
> - PyPI Trusted Publishers
> - Maven Central GPG signatures
> 
> The package you install is what the maintainer published.
> 
> ### SBOM (Software Bill of Materials)
> 
> A list of everything in a build: every library, every transitive dep, every version, every license.
> 
> Standard formats: SPDX, CycloneDX.
> 
> Generated at build; published with releases. Customers can verify what's in the software.
> 
> ### SLSA framework
> 
> Supply-chain Levels for
> … (truncated)


- [ ] verdict: ______  (OK / DROP / FIX → ____)


---

## q15 — SIMILARITY

**Query:** onboarding a new client


**Gold 1:** `CustomerRelationshipManagement.md` → `I. Foundations: The Evolution of CRM`

> **I. Foundations: The Evolution of CRM**
>
> The definition of CRM has undergone an ontological shift. We move beyond managing interactions to managing the *definition* of the relationship itself.
> 
> ### 1.1 From Operational to Analytical CRM
> *   **Operational CRM:** Focuses on automating front-office processes (Sales, Marketing, Support). It is the baseline for data collection.
> *   **Analytical CRM:** Uses [Machine Learning](MachineLearning) to derive insights (CLV, Churn prediction) from operational data. It provides the "Why" behind the "What."
> 
> ### 1.2 The System of Record (SoR)
> A mature CRM strategy mandates that the CRM is the single source of truth for customer identity. This requires rigorous [Business Process Modeling](BusinessProcessModeling) to ensure that data flows seamlessly from disparate touchpoints into the core profile.
> 
> ---


- [ ] verdict: ______  (OK / DROP / FIX → ____)


---

## q16 — SIMILARITY

**Query:** how to plan a sprint


**Gold 1:** `SprintPlanning.md` → `III. The Planning Workflow`

> **III. The Planning Workflow**
>
> 1.  **Verify Definition of Ready (DoR):** Do the top stories have clear Acceptance Criteria?
> 2.  **Calculate Capacity:** Subtract PTO, holidays, and scheduled maintenance.
> 3.  **Select Stories:** Pull from the backlog based on priority until the sum of points$\approx$ Adjusted Velocity.
> 4.  **Task Out:** Break stories into sub-tasks (usually 2-6 hours each). If the sum of task hours > Available Capacity, the story must be removed.
> 
> ---


- [ ] verdict: ______  (OK / DROP / FIX → ____)


---

## q17 — SIMILARITY

**Query:** handling a production outage


**Gold 1:** `IncidentManagement.md` → `1. Incident Lifecycle and Severities`

> **1. Incident Lifecycle and Severities**
>
> Every incident must be categorized to dictate the response level:
> *   **SEV-1 (Critical):** Core service is down for all users (e.g., Checkout is broken). Immediate "War Room" required.
> *   **SEV-2 (High):** Significant degradation for a subset of users. Response within 30 minutes.
> *   **SEV-3 (Medium):** Minor bug or non-critical feature failure. Resolved during business hours.
> 
> ---


- [ ] verdict: ______  (OK / DROP / FIX → ____)


---

## q18 — SIMILARITY

**Query:** roadmap planning process


**Gold 1:** `ProductRoadmapping.md` → `I. The Hierarchy of Product Intent`

> **I. The Hierarchy of Product Intent**
>
> Effective roadmapping requires a cascading hierarchy where each level constrains and informs the next. Misalignment between these levels leads to feature bloat and wasted engineering resources.
> 
> 1.  **Product Vision:** The long-term aspirational state. It defines the ultimate impact on the user or industry (e.g., "Making enterprise sales cycles as intuitive as personal conversations").
> 2.  **Product Strategy:** The competitive thesis. It identifies the target market, unique value proposition (UVP), and key differentiators. Strategy must be testable through measurable hypotheses.
> 3.  **Product Roadmap:** The execution timeline. It sequences deliverables to validate strategic hypotheses. It should reflect time horizons (Now, Next, Later) rather than rigid, unchangeable dates.
> 
> ### Testing Strategic Hypotheses
> 
> Every initiative on the roadmap should be framed as a testable hypothesis to ensure validated learning:
> 
> $$
> \text{Hypothesis} = \text{If we build } (X) \text{ for } (Y) \text{ segment, then we expect } (Z) \text{ metric change, because of } (A) \text{ assumption.}
> $$
> 
> ---


- [ ] verdict: ______  (OK / DROP / FIX → ____)


---

## q19 — SIMILARITY

**Query:** code review best practices


**Gold 1:** `CodeReviewPractices.md` → `II. The Expert Checklist`

> **II. The Expert Checklist**
>
> We categorize review criteria into orthogonal dimensions of system quality:
> *   **Security:** Moving beyond OWASP to trace data flow tainting and verifying **AuthZ** at the resource level.
> *   **Maintainability:** Enforcing the **Principle of Least Astonishment (POLA)** and preventing abstraction leakage.
> *   **Performance:** Conducting asymptotic analysis (Big-O) and reviewing caching/staleness tolerance.
> 
> ---


- [ ] verdict: ______  (OK / DROP / FIX → ____)


---

## q20 — SIMILARITY

**Query:** admin ui for security settings


**Gold 1:** `AdminSecurityUi.md` → `Features`

> **Features**
>
> - **User management** — List, search, create, edit, delete users
> - **Group management** — Create groups, manage membership
> - **Permission policies** — Edit role-based permissions from the database-backed `policy_grants` table
> - **Role assignment** — Assign users to groups with immediate effect (no restart required)


- [ ] verdict: ______  (OK / DROP / FIX → ____)


---

## q21 — SIMILARITY

**Query:** role based access permissions stored in the database


**Gold 1:** `DatabaseBackedPermissions.md` → `Key Tables`

> **Key Tables**
>
> - `policy_grants` — Maps roles to permissions (view, edit, upload, etc.)
> - `groups` — Wiki groups (Admin, Authenticated, etc.)
> - `group_members` — Group membership assignments


- [ ] verdict: ______  (OK / DROP / FIX → ____)


---

## q22 — SIMILARITY

**Query:** k8s


**Gold 1:** `KubernetesBasics.md` → `The Atomic Unit: The Pod`

> **The Atomic Unit: The Pod**
>
> A **Pod** is the smallest deployable unit. It groups one or more containers that share a network namespace (localhost) and storage volumes.
> 
> ### The Sidecar Pattern
> The Pod's shared network namespace enables the **Sidecar Pattern**, where auxiliary tasks (logging, service mesh proxying, secret rotation) are decoupled from the primary application container.
> 
> **Technical Constraint:** All containers in a Pod share a single IP. Port conflicts must be managed at the container level within the Pod.


- [ ] verdict: ______  (OK / DROP / FIX → ____)


---

## q23 — SIMILARITY

**Query:** ci cd


**Gold 1:** `CiCdPipelines.md` → `The stages`

> **The stages**
>
> A typical pipeline:
> 
> 1. **Trigger**: code push, PR, schedule, manual
> 2. **Build**: compile, package; produce artifact
> 3. **Unit test**: fast tests, run in parallel
> 4. **Static analysis**: linting, type checking, security scanning
> 5. **Integration test**: tests with real dependencies (DB, cache)
> 6. **Artifact storage**: the build output, versioned
> 7. **Deploy to environments**: dev → staging → production
> 8. **Post-deploy verification**: smoke tests, canary checks
> 
> Each stage gates the next. Failures stop the pipeline.


- [ ] verdict: ______  (OK / DROP / FIX → ____)


---

## q24 — SIMILARITY

**Query:** backup strategy for the database


**Gold 1:** `DatabaseBackupStrategies.md` → `Backup types`

> **Backup types**
>
> ### Full backup
> 
> Complete copy of the database. Largest; longest to take; longest to restore.
> 
> ### Incremental backup
> 
> Changes since last backup (full or incremental). Smaller; faster; chains together for restore.
> 
> ### Differential backup
> 
> Changes since last full backup. Larger than incremental but simpler restore.
> 
> ### Continuous archiving / WAL shipping
> 
> PostgreSQL: write-ahead log files shipped continuously. Enables point-in-time recovery to any moment.
> 
> For most production systems, continuous archiving + periodic full backups is the standard.


- [ ] verdict: ______  (OK / DROP / FIX → ____)


---

## q25 — SIMILARITY

**Query:** schema migration approach


**Gold 1:** `DatabaseMigrationStrategies.md` → `Migration discipline rules`

> **Migration discipline rules**
>
> Before any migration:
> 
> 1. **It's a versioned file**, named with a sequence number or timestamp.
> 2. **It's idempotent**: re-running it produces the same outcome (use `IF NOT EXISTS`, `ON CONFLICT DO NOTHING`).
> 3. **It's reviewed**: in the same PR as the application change that needs it.
> 4. **It's never edited after merge**: if you got it wrong, write a new migration.
> 5. **It's tested**: applied to staging that reflects production-scale data.
> 
> The "never edited after merge" rule prevents the worst kind of drift: prod has migration v17 that's different from v17 in dev because someone rewrote it.


- [ ] verdict: ______  (OK / DROP / FIX → ____)


---

## q26 — SIMILARITY

**Query:** semantic search with embeddings


**Gold 1:** `AiPoweredSearch.md` → `The minimum modern stack`

> **The minimum modern stack**
>
> ```
>         ┌──────────────────────────────┐
> query ─▶│    Query understanding       │
>         │  (rewrite, decompose, expand)│
>         └──────────┬───────────────────┘
>                    │
>         ┌──────────┴────────────┐
>         ▼                       ▼
>   ┌──────────┐           ┌─────────────┐
>   │ BM25     │           │ Dense       │
>   │ search   │           │ retrieval   │
>   │ (Lucene) │           │ (HNSW)      │
>   └────┬─────┘           └──────┬──────┘
>        │                        │
>        └────────────┬───────────┘
>                     ▼
>             ┌──────────────┐
>             │ RRF fusion   │
>             └──────┬───────┘
>                    ▼
>             ┌──────────────┐
>             │  Rerank      │
>             │ (cross-enc)  │
>             └──────┬───────┘
>                    ▼
>             ┌──────────────┐
>             │ Answer synth │
>             │ + citations  │
>             └──────────────┘
> ```
> 
> Each stage is independently swappable. This pipeline is what most production "AI search" looks like under the hood; calling it AI search overstates the AI's role and understates how much classical IR is still doing the work.
> 
> See [HybridRetrieval]() for this wiki's own implementati
> … (truncated)


- [ ] verdict: ______  (OK / DROP / FIX → ____)


---

## q27 — SIMILARITY

**Query:** knowledge graph retrieval


**Gold 1:** `GraphRAG.md` → `Querying the graph`

> **Querying the graph**
>
> ### Direct graph queries
> 
> Cypher (Neo4j), SPARQL (RDF), Gremlin (TinkerPop).
> 
> Powerful but require schema knowledge.
> 
> ### Natural language to graph query
> 
> LLM translates question to graph query language.
> 
> Quality depends on schema clarity and example coverage.
> 
> ### Path queries
> 
> "Find paths from X to Y." Useful for knowledge exploration.
> 
> ### Graph algorithms
> 
> PageRank for importance, community detection for clusters, shortest path for relationships.


- [ ] verdict: ______  (OK / DROP / FIX → ____)


---

## q28 — SIMILARITY

**Query:** images and text embeddings together


**Gold 1:** `MultimodalEmbeddings.md` → `1. Architecture: The Projection Layer`

> **1. Architecture: The Projection Layer**
>
> Modern multimodal models (CLIP, SigLIP, ImageBind) consist of independent encoders for each modality, followed by a **projection layer** that aligns their outputs.
> 
> ### CLIP (Contrastive Language-Image Pretraining)
> CLIP uses a dual-encoder architecture. The training objective is to maximize the cosine similarity of$N$correct pairs in a batch while minimizing the similarity of the$N^2 - N$incorrect pairs.
> 
> $$
> \mathcal{L} = \frac{1}{2N} \sum_{i=1}^N \left( \log \frac{\exp(\cos(\mathbf{t}_i, \mathbf{v}_i)/\tau)}{\sum_{j=1}^N \exp(\cos(\mathbf{t}_i, \mathbf{v}_j)/\tau)} + \log \frac{\exp(\cos(\mathbf{t}_i, \mathbf{v}_i)/\tau)}{\sum_{j=1}^N \exp(\cos(\mathbf{t}_j, \mathbf{v}_i)/\tau)} \right)
> $$
> 
> where$\mathbf{t}$is text,$\mathbf{v}$is vision, and$\tau$is a learnable temperature parameter.
> ### SigLIP (Sigmoid Loss)
> SigLIP (Google, 2023) replaces the softmax over the whole batch with a simple pairwise sigmoid loss. This allows for much larger batch sizes and better stability on small GPUs.


- [ ] verdict: ______  (OK / DROP / FIX → ____)


---

## q29 — SIMILARITY

**Query:** how agents remember things


**Gold 1:** `AgentMemory.md` → `The Four Memory Channels`

> **The Four Memory Channels**
>
> | Channel | Lifetime | Storage Substrate | Content |
> |---|---|---|---|
> | **Scratch Reasoning** | Single turn | Model Context | Internal "Chain of Thought" or reasoning steps. |
> | **Tool History** | Current loop | Model Context (Summarized) | Sequence of tool calls, arguments, and results. |
> | **Working Memory** | Current task | System Prompt / Structured Data | Extracted facts (e.g., `user_id`, `plan_status`) that must survive summarization. |
> | **Long-Term Memory** | Cross-session | Vector DB / SQL / Graph | User preferences, past conversation summaries, persistent knowledge. |
> 
> ---


- [ ] verdict: ______  (OK / DROP / FIX → ____)


---

## q30 — SIMILARITY

**Query:** evaluating and monitoring llm agents in production


**Gold 1:** `AgentObservability.md` → `The three observability layers`

> **The three observability layers**
>
> Agents need the same three layers as any distributed system — metrics, logs, traces — but the semantics differ.
> 
> | Layer | HTTP service | LLM agent |
> |---|---|---|
> | **Metrics** | req/s, error rate, p50/p95 latency | task success rate, cost per task, tool validity rate, token throughput |
> | **Logs** | structured app logs | structured app logs + full LLM inputs/outputs (sampled) |
> | **Traces** | one span per service hop | one span per LLM call and one per tool dispatch, nested under a per-task trace |
> 
> Metrics tell you that something's wrong. Traces tell you what and where. Logs contain the prompts you need to reproduce. You need all three.


- [ ] verdict: ______  (OK / DROP / FIX → ____)


---

## q31 — SIMILARITY

**Query:** prompt engineering for agents


**Gold 1:** `AgentPromptEngineering.md` → `System prompt structure`

> **System prompt structure**
>
> Effective agent system prompts have a consistent structure:
> 
> ```
> [Role]
> You are an agent that ___.
> 
> [Tools]
> Available tools: ___ (with descriptions)
> 
> [Process]
> For each task:
> 1. ___
> 2. ___
> 3. ___
> 
> [Output format]
> Tool calls in format: ___
> Final answer in format: ___
> 
> [Constraints]
> Never: ___
> Always: ___
> 
> [Error handling]
> If a tool fails: ___
> If you're stuck: ___
> ```
> 
> Each section serves a specific purpose. Skipping any creates failure modes.


- [ ] verdict: ______  (OK / DROP / FIX → ____)


---

## q32 — SIMILARITY

**Query:** test driven development


**Gold 1:** `TestDrivenDevelopment.md` → `TDD Workflow for AI Agents`

> **TDD Workflow for AI Agents**
>
> 1. **Research:** Understand the current behavior and existing tests.
> 2. **Reproduction:** Write a failing test case that demonstrates the bug or the missing feature.
> 3. **Execution:** Implement the minimal code change needed to make the test pass.
> 4. **Validation:** Run the full module test suite (`mvn test -pl <module>`) to ensure no regressions.


- [ ] verdict: ______  (OK / DROP / FIX → ____)


---

## q33 — SIMILARITY

**Query:** writing technical docs


**Gold 1:** `TechnicalWritingGuide.md` → `The four documents that matter most`

> **The four documents that matter most**
>
> ### Design documents
> 
> A design doc proposes a solution to a problem before implementation begins. The structure that works:
> 
> 1. **Title and one-paragraph summary.** What is being decided.
> 2. **Context.** Why is this work being done?
> 3. **Goals and non-goals.** What are we trying to achieve; what are we explicitly not addressing?
> 4. **Proposed approach.** What we plan to do.
> 5. **Alternatives considered.** What other approaches we evaluated and why we rejected them.
> 6. **Risks and open questions.** What could go wrong; what we still need to figure out.
> 7. **Plan.** Sequencing, milestones, owners.
> 
> A good design doc is 2–10 pages. Anything longer is usually under-edited.
> 
> ### Runbooks
> 
> A runbook is operational documentation — what to do when something happens. The structure:
> 
> 1. **Symptoms.** What does the problem look like? Specifically the alert, the error message, the customer report.
> 2. **Initial actions.** First diagnostic steps. Specific commands.
> 3. **Common causes.** What usually causes this; how to identify which one.
> 4. **Resolution per cause.** Specific steps to fix.
> 5. **Escalation.** Who to call if the runbook doesn't help.
> 
> Runbooks are dramatically more useful than abs
> … (truncated)


- [ ] verdict: ______  (OK / DROP / FIX → ____)


---

## q34 — SIMILARITY

**Query:** setting up docker for development


**Gold 1:** `DockerDeployment.md` → `1. Configuration`

> **1. Configuration**
>
> Wikantik's Docker container is highly configurable through environment variables. This allows you to customize your installation without modifying the core application files.
> 
> ### Environment Variables
> 
> The following environment variables are available to configure your Wikantik instance. You can set them in your `docker-compose.yml` file or directly with the `docker run` command.
> 
> - `CATALINA_OPTS`: Additional options for the Tomcat server. The default value, `-Djava.security.egd=file:/dev/./urandom`, is recommended for better performance on systems with low entropy.
> - `LANG`: Sets the language for the container. Defaults to `en_US.UTF-8`.
> - `jspwiki_basicAttachmentProvider_storageDir`: The directory where attachments are stored. Defaults to `/var/jspwiki/pages`.
> - `jspwiki_fileSystemProvider_pageDir`: The directory where wiki pages are stored. Defaults to `/var/jspwiki/pages`.
> - `jspwiki_frontPage`: The name of the wiki's front page. Defaults to `Main`.
> - `jspwiki_pageProvider`: The page provider to use. Defaults to `VersioningFileProvider`.
> - `jspwiki_use_external_logconfig`: Set to `true` to use an external Log4j2 configuration file. Defaults to `true`.
> - `jspwiki_workDir`: The
> … (truncated)


- [ ] verdict: ______  (OK / DROP / FIX → ____)


---

## q35 — SIMILARITY

**Query:** ai


**Gold 1:** `ArtificialIntelligence.md` → `Definition and Scope`

> **Definition and Scope**
>
> At its core, AI is the science of making machines perform tasks that would require intelligence if done by humans. This definition, while broad, captures the field's ambition: to replicate or augment cognitive functions such as perception, reasoning, learning, and problem-solving. The scope of AI ranges from narrow systems designed for a single task (like playing chess or recognizing faces) to theoretical constructs like artificial general intelligence that would match or exceed human capabilities across all domains.


- [ ] verdict: ______  (OK / DROP / FIX → ____)


---

## q36 — SIMILARITY

**Query:** jvm gc


**Gold 1:** `JavaMemoryManagement.md` → `III. Modern GC Comparison: G1 vs. ZGC`

> **III. Modern GC Comparison: G1 vs. ZGC**
>
> | Feature | G1 GC (Balanced) | ZGC (Latency Focused) |
> | :--- | :--- | :--- |
> | **Heap Structure** | Regions (fixed size) | Regions (dynamic size) |
> | **Max Pause Time** | Target-based (~200ms) | Sub-millisecond |
> | **Throughput** | High | Medium (due to load barriers) |
> | **Best For** | General apps, large heaps | Low-latency, huge heaps (>32GB) |


- [ ] verdict: ______  (OK / DROP / FIX → ____)


---

## q37 — SIMILARITY

**Query:** overview of agent architectures


**Gold 1:** `AgenticArchitecture.md` → `The composition spectrum`

> **The composition spectrum**
>
> Three positions a system can take on agency:
> 
> | Position | Example | When it fits |
> |---|---|---|
> | **Tool of an agent** | Application is a tool the agent calls. Agent is in charge. | Open-ended assistant tasks, research helpers |
> | **Pipeline with agent steps** | Application is in charge; agent handles certain steps where flexibility is needed | Most production usage; structured workflows with one or two agentic stages |
> | **Agent of a tool** | Application is the system; agent is invoked when it needs help (e.g., from a help button). | Mostly traditional with optional agent assistance |
> 
> Most production systems are in the middle: a structured workflow where one or two steps need an agent. Pure agent-in-charge architectures are rare in production because they're hard to bound, hard to evaluate, and hard to make compliant.


- [ ] verdict: ______  (OK / DROP / FIX → ____)


---

## q38 — SIMILARITY

**Query:** objectives and key results


**Gold 1:** `OkrsAndGoalSetting.md` → `I. The Structural Components`

> **I. The Structural Components**
>
> ### A. The Objective ($\text{O}$)
> The Objective is a qualitative, aspirational statement of a desired future state.
> *   **Property:** Directional and non-negotiable in intent.
> *   **Anti-Pattern:** "Increase user engagement" (Too measurable, lacks "why").
> *   **Practitioner Pattern:** "Establish the platform as the industry benchmark for API reliability and developer self-service."
> 
> ### B. The Key Result ($\text{KR}$)
> The KR is the empirical proof point. It follows the formula:
> 
> $$
> \text{KR} = \text{Measure} \rightarrow \text{Baseline} \rightarrow \text{Target} \times \text{Deadline}
> $$
> 
> #### Leading vs. Lagging Indicators*   **Lagging (Outcome):** "Increase revenue by 20%." (Too late to influence).
> *   **Leading (Predictor):** "Reduce P99 latency for checkout flow from 500ms to 200ms." (Predicts improved conversion).
> 
> ---


- [ ] verdict: ______  (OK / DROP / FIX → ____)


---

## q39 — SIMILARITY

**Query:** setting goals for the quarter


**Gold 1:** `OkrsAndGoalSetting.md` → `II. The Math of Stretch Goals`

> **II. The Math of Stretch Goals**
>
> Advanced OKR systems distinguish between **Committed** (expected to hit 100%) and **Aspirational/Stretch** (expected to hit 60-70%) goals.
> 
> ### A. Probability Modeling of Targets
> If we treat a Key Result's progress as a random variable$X$, a "stretch" target$T$is chosen such that the probability of full achievement is low, but the expected value drives maximum effort.
> 
> Using a normal distribution$X \sim \mathcal{N}(\mu, \sigma^2)$where$\mu$is the realistic capacity and$\sigma$is the volatility:
> *   **Committed Target:**$T_c \approx \mu - \sigma$(High confidence of success).
> *   **Stretch Target:**$T_s \approx \mu + 1.5\sigma$(Only ~7% probability of hitting 100%, but pushes the boundary of$\mu$).
> 
> ### B. Scoring Mechanics
> The typical OKR score$S$is normalized between 0.0 and 1.0:
> 
> $$
> S = \min\left(1, \frac{\text{Actual} - \text{Baseline}}{\text{Target} - \text{Baseline}}\right)
> $$
> 
> *   **Sweet Spot:**$0.7$. A team consistently hitting$1.0$is sandbagging; a team hitting$0.3$is disconnected from reality or under-resourced.
> ---


- [ ] verdict: ______  (OK / DROP / FIX → ____)


---

## q40 — SIMILARITY

**Query:** measuring what matters


**Gold 1:** `BusinessMetricsAndKpis.md` → `II. Leading vs. Lagging Indicators`

> **II. Leading vs. Lagging Indicators**
>
> The most critical distinction for practitioners is between the outcome and the precursor.
> *   **Lagging Indicators:** Report what has already happened (e.g., Quarterly Revenue). They are high-fidelity but reactive.
> *   **Leading Indicators:** Report on precursors that correlate with future outcomes (e.g., Sales Pipeline health, P99 latency). They are predictive and actionable. See [Monitoring and Observability](MonitoringAndAlerting) for leading indicators in technical systems.
> 
> ---


- [ ] verdict: ______  (OK / DROP / FIX → ____)


---

## r01 — RELATIONAL

**Query:** how does Wikantik hybrid retrieval decide between BM25 and dense results


**Gold 1:** `HybridRetrieval.md` → `Wiring`

> **Wiring**
>
> The retrieval stack is assembled during engine init by `WikiEngine.wireHybridRetrieval()`:
> 
> 1. **EmbeddingConfig** — resolves backend + model from `wikantik.properties`. If the master flag is off, nothing else wires up.
> 2. **TextEmbeddingClient** — backend adapter (currently Ollama). Used by both index-time and query-time paths.
> 3. **EmbeddingIndexService** + **AsyncEmbeddingIndexListener** — hook into `ChunkProjector` so every page save re-embeds the new chunks.
> 4. **InMemoryChunkVectorIndex** — loads the embeddings table at construction and reloads after each batch so queries always see the latest corpus without hitting Postgres.
> 5. **QueryEmbedder** — wraps the client with a Caffeine cache, a timeout budget, and a hand-rolled circuit breaker (`CLOSED → HALF_OPEN → OPEN`).
> 6. **DenseRetriever** + **HybridFuser** + **HybridSearchService** — the query-time orchestrator. Registered as a manager so `SearchResource` can use it transparently.
> 7. **BootstrapEmbeddingIndexer** — one-shot state machine that backfills the embeddings table if it is empty for the current model. Runs async; exposed via the admin index-status panel.
> 8. **HybridMetricsBridge** — publishes embedder, bootstrap, a
> … (truncated)


**Gold 2:** `HybridRetrieval.md` → `Fail-closed behaviour`

> **Fail-closed behaviour**
>
> `HybridSearchService.rerank()` is the single choke point that guarantees BM25-only is always a valid fallback. Every abnormal path returns the input BM25 list unchanged:
> 
> | Trigger                                    | Result                                  |
> |--------------------------------------------|-----------------------------------------|
> | `wikantik.search.hybrid.enabled = false`   | BM25 verbatim                           |
> | Query null / blank                         | BM25 verbatim                           |
> | `QueryEmbedder.embed()` throws             | BM25 verbatim, WARN logged              |
> | Embedder returns `Optional.empty()`        | BM25 verbatim, DEBUG logged             |
> | `DenseRetriever.retrieve()` throws         | BM25 verbatim, WARN logged              |
> | Dense result set empty                     | BM25 verbatim                           |
> | Vector index not ready                     | BM25 verbatim (dense returns empty)     |
> | Circuit breaker OPEN                       | BM25 verbatim (embedder returns empty)  |
> 
> A fused response is a superset of BM25: pages that appeared only in dense results are appended after the fused block as `DenseOnlySearchRe
> … (truncated)


- [ ] verdict: ______  (OK / DROP / FIX → ____)


---

## r02 — RELATIONAL

**Query:** what configuration enables the Knowledge Graph rerank and what is the default


**Gold 1:** `KnowledgeGraphRerank.md` → `Configuration`

> **Configuration**
>
> Reranking behavior is controlled via `wikantik-custom.properties`:
> 
> ```properties


**Gold 2:** `HybridRetrieval.md` → `Configuration`

> **Configuration**
>
> All keys live in `wikantik.properties`; override in `wikantik-custom.properties` or set from the environment.
> 
> ### Master flag
> 
> ```
> wikantik.search.hybrid.enabled = true
> ```
> 
> Default in the shipped properties file is `true`. Setting `false` disables both the dense index build and the query-time rerank — no embedding client is instantiated.
> 
> ### Fuser
> 
> ```
> wikantik.search.hybrid.rrf.k            = 60
> wikantik.search.hybrid.rrf.bm25-weight  = 1.0
> wikantik.search.hybrid.rrf.dense-weight = 1.5
> wikantik.search.hybrid.rrf.truncate     = 20
> wikantik.search.hybrid.page-aggregation = SUM_TOP_3
> ```
> 
> These are the eval-winning defaults from the `retrieval-eval-baseline` run. `rrf.truncate` caps the fused window; pages outside both top-20 lists fall out — `HybridSearchService` appends any BM25 tail back to the end of the output so reorder-not-remove stays true.
> 
> ### Dense retrieval
> 
> ```
> wikantik.search.hybrid.dense.chunk-top = 500
> wikantik.search.hybrid.dense.page-top  = 100
> ```
> 
> `chunk-top` is the cosine top-K from the in-memory index; `page-top` caps how many pages survive after `PageAggregation.SUM_TOP_3` collapses their chunk scores.
> 
> ### Dense backend selection
> 
> ```
> wikantik.search.dense.
> … (truncated)


- [ ] verdict: ______  (OK / DROP / FIX → ____)


---

## r04 — RELATIONAL

**Query:** what embedding model does the Wikantik retrieval harness use and why was it chosen


**Gold 1:** `RetrievalExperimentHarness.md` → `7. Model selection — the 2026-04-18 decision`

> **7. Model selection — the 2026-04-18 decision**
>
> This is the run that picked `qwen3-embedding-0.6b` as the production
> embedding model. All three candidates indexed the same ~30k-chunk corpus,
> same BM25 baseline, same 40-query / 7-category ground truth, same
> max-score page aggregation at that point.
> 
> ### Raw results
> 
> | Model | dim | bm25 r@5 | dense r@5 | dense r@20 | dense MRR | hybrid r@5 | hybrid r@20 | hybrid MRR |
> |---|---|---|---|---|---|---|---|---|
> | `nomic-embed-v1.5` | 768 | 0.550 | 0.625 | 0.800 | 0.474 | 0.650 | 0.900 | 0.530 |
> | `bge-m3` | 1024 | 0.550 | 0.700 | 0.875 | 0.503 | 0.750 | 0.900 | 0.615 |
> | **`qwen3-embedding-0.6b`** | 1024 | 0.550 | **0.750** | **0.900** | 0.490 | **0.750** | **0.925** | 0.602 |
> 
> Reports on disk: `eval/report-nomic-embed-v1.5.txt`,
> `eval/report-bge-m3.txt`, `eval/report-qwen3-embedding-0.6b.txt`.
> 
> ### Decision rationale
> 
> - **qwen3 leads on recall at both cutoffs.** Dense recall@5 is a full
>   +0.050 over bge-m3 and +0.125 over nomic. Dense recall@20 is +0.025
>   over bge-m3 and +0.100 over nomic. Hybrid recall@20 (0.925) is the
>   best across the three.
> - **bge-m3 leads narrowly on MRR** (dense 0.503 vs 0.490; hybrid 0.615
>   vs 0.602). This was known, weighed, and accepted: for a RAG pipeli
> … (truncated)


- [ ] verdict: ______  (OK / DROP / FIX → ____)


---

## r05 — RELATIONAL

**Query:** how do you add a page to the Knowledge Graph and what controls inclusion


**Gold 1:** `KgInclusionPolicy.md` → `The decision model`

> **The decision model**
>
> For any page, the system evaluates four steps in order and stops at the first
> one that applies:
> 
> 1. **System page?** Sandbox, Main, navigation pages, etc. Always excluded.
> 2. **`kg_include: false` in frontmatter?** Excluded, regardless of cluster.
> 3. **`kg_include: true` in frontmatter?** Included, regardless of cluster.
> 4. **Cluster policy.** If the page's cluster has an `include` row in
>    `kg_cluster_policy`, the page is included. Otherwise excluded.
> 
> The default is **exclude** — a cluster you haven't touched contributes nothing
> to the KG. This is deliberate: imports of new content can't sneak into agent
> retrieval before you've reviewed them.
> 
> The page's cluster is read from frontmatter (`cluster: <name>`); see
> [StructuralSpineDesign](StructuralSpineDesign).


**Gold 2:** `KgInclusionPolicy.md` → `Agent curation path`

> **Agent curation path**
>
> Curator agents should drive proposal triage through `/wikantik-admin-mcp` rather
> than the REST surface:
> 
> - `list_proposals` — filtered listing with conflict flags
>   (`node_exists`, `edge_previously_rejected`)
> - `inspect_proposals` — bulk deep-dive (1..50 ids) with prior reviews
> - `review_proposals` — bulk `approve | reject | judge` (1..50 ids; `reject`
>   requires a top-level `reason`)
> - `curate_edges` / `curate_nodes` — heterogeneous bulk ops (1..50 ops)
> 
> See `docs/superpowers/specs/2026-05-13-kg-curation-mcp-design.md` for the full
> envelope and error contract.
> 
> ### Admin-bypass on read paths
> 
> Admin-context reads bypass the inclusion filter so curators see entities
> they just created, even when the source page hasn't been admitted by the
> cluster policy yet. The bypass applies to:
> 
> - REST `/admin/knowledge-graph/*` reads (already gated by `AdminAuthFilter`).
> - The MCP tools registered on `/wikantik-admin-mcp` — `list_proposals`,
>   `inspect_proposals`, and the new admin-bypass copies of `query_nodes`
>   and `search_knowledge` (24 tools total).
> 
> The agent-facing `/knowledge-mcp` server keeps the filter on, so retrieval
> quality is unchanged. See
> `docs/superpowers/specs/2026-05-14-kg-cura
> … (truncated)


- [ ] verdict: ______  (OK / DROP / FIX → ____)


---

## r06 — RELATIONAL

**Query:** how does chunking strategy affect RAG quality and what pattern should I use


**Gold 1:** `RagImplementationPatterns.md` → `The second biggest: chunking strategy`

> **The second biggest: chunking strategy**
>
> "How do I chunk my documents" is the second question every RAG system gets wrong. The trap is either (a) fixed-size 500-token chunks that cut sentences in half, or (b) a section-header splitter that produces one 8000-token chunk and ten 50-token ones.
> 
> Working strategy:
> 
> 1. **Split on semantic boundaries first** — headings, paragraph breaks, list items. Never mid-sentence.
> 2. **Size target: 256–512 tokens per chunk.** Smaller hurts context; larger dilutes embeddings.
> 3. **Overlap adjacent chunks by 10–20%.** One-sentence overlap usually suffices; prevents information loss at boundaries.
> 4. **Attach context metadata.** Every chunk carries `{document_id, section_path, preceding_heading}`. Your retriever uses the heading path for filtering and the context in the prompt.
> 
> For structured content (code, tables, JSON) stop before applying text chunking. Treat code blocks and tables as atomic units. A chunk that contains half a table is actively harmful — the model will hallucinate the missing rows.


**Gold 2:** `AiPoweredSearch.md` → `Stage by stage`

> **Stage by stage**
>
> ### Query understanding
> 
> The query the user typed is rarely the right query for retrieval. Three useful transformations:
> 
> - **Rewrite** — fix typos, normalise casing, expand abbreviations. Cheap; can be done with rules or a tiny LLM.
> - **Decompose** — split "compare X and Y on metric Z" into three sub-queries. Necessary for multi-entity or multi-aspect queries.
> - **Expand (HyDE)** — generate a hypothetical answer with an LLM, embed *that* for retrieval. Helps when the query is short ("k8s networking issues" → a paragraph about typical k8s networking failures, embedded for vector search).
> 
> Don't apply all three. Each adds latency. Profile which transformation actually improves retrieval recall on your eval set; keep that one.
> 
> ### Hybrid retrieval
> 
> BM25 and dense retrieval miss different things. BM25 misses paraphrases ("car" vs "automobile"). Dense retrieval misses exact strings ("error code 451"). Run both, fuse with reciprocal rank fusion (RRF) or learned-to-rank.
> 
> RRF in five lines:
> 
> ```python
> def rrf(*ranked_lists, k=60):
>     scores = {}
>     for hits in ranked_lists:
>         for rank, hit in enumerate(hits):
>             scores[hit.id] = scores.get(hit.id, 0) + 1 / (k + rank + 1
> … (truncated)


- [ ] verdict: ______  (OK / DROP / FIX → ____)


---

## r07 — RELATIONAL

**Query:** what metrics does the retrieval experiment harness compare and how do I run it


**Gold 1:** `RetrievalExperimentHarness.md` → `1. What gets compared`

> **1. What gets compared**
>
> | Retriever | Source | Notes |
> |---|---|---|
> | **BM25-only** | `GET /api/search?q=…` on the running wiki | Lucene lexical baseline |
> | **Dense-only** | Cosine similarity over per-chunk vectors, aggregated to pages by max-score | One run per candidate model |
> | **Hybrid** | Reciprocal Rank Fusion (k=60) of the two rankings above | |
> 
> Three candidate models (all served by Ollama at `inference.jakefear.com:11434`):
> 
> | Code | Ollama tag | Dimension | Asymmetric prefix |
> |---|---|---|---|
> | `nomic-embed-v1.5` | `nomic-embed-text:v1.5` | 768 | `search_query:` / `search_document:` |
> | `bge-m3` | `bge-m3:latest` | 1024 | none |
> | `qwen3-embedding-0.6b` | `qwen3-embedding:0.6b` | 1024 | instruction prompt on queries only |
> 
> Each run produces `eval/report-<model>.txt` with overall, per-category, and
> per-query metrics (recall@5, recall@20, MRR). `ExperimentCompare` then prints
> a side-by-side table across all three reports.
> 
> ---


**Gold 2:** `RetrievalExperimentHarness.md` → `3. One-shot run`

> **3. One-shot run**
>
> The full pipeline (DDL → indexer × 3 → evaluator × 3 → compare) is wrapped by
> `bin/run-embedding-experiment.sh`:
> 
> ```
> source <(grep -v '^#' test.properties | sed 's/^test.user.//' | sed 's/=/="/' | sed 's/$/"/')
> 
> DB_PASSWORD='<jspwiki db pw>' \
> WIKI_USER="${login}" WIKI_PASSWORD="${password}" \
>     bin/run-embedding-experiment.sh
> ```
> 
> Required env: `DB_PASSWORD`, `WIKI_USER`, `WIKI_PASSWORD`.
> 
> Optional env:
> 
> | Var | Default | Purpose |
> |---|---|---|
> | `MODELS` | all three codes | Space-separated subset to test |
> | `DB_HOST` / `DB_NAME` / `DB_USER` | `localhost` / `jspwiki` / `jspwiki` | |
> | `WIKI_URL` | `http://localhost:8080` | |
> | `OUTPUT_DIR` | `eval` | Where reports land |
> | `SKIP_DDL=1` | off | Skip the DDL step |
> | `SKIP_INDEX=1` | off | Skip indexer (re-score existing embeddings) |
> | `MVN_QUIET=1` | off | `-q` on Maven (cuts chatter) |
> 
> The indexer fails fast with a clear message if `kg_content_chunks` is empty —
> you'll see it immediately rather than after a silent 0-row run.
> 
> ---


- [ ] verdict: ______  (OK / DROP / FIX → ____)


---

## r08 — RELATIONAL

**Query:** how does canary deployment differ from blue-green and when should I use each


**Gold 1:** `CanaryDeployments.md` → `1. Traffic Splitting Mechanisms`

> **1. Traffic Splitting Mechanisms**
>
> The core of a canary is the ability to route a precise percentage of traffic.
> 
> *   **Layer 4 (IP-based):** Simple, but lacks granularity. Often used at the Load Balancer level (e.g., AWS Target Group weights).
> *   **Layer 7 (Request-based):** Superior for modern APIs. Routes based on headers, cookies, or user IDs.
> *   **Concrete Example (Istio):** Use a `VirtualService` to route 90% of traffic to the `stable` subset and 10% to the `canary` subset.
>     ```yaml
>     http:
>     - route:
>       - destination: {host: my-svc, subset: stable}, weight: 90
>       - destination: {host: my-svc, subset: canary}, weight: 10
>     ```


**Gold 2:** `BlueGreenDeployments.md` → `When canary wins`

> **When canary wins**
>
> For most modern web applications, canary deployment beats blue-green:
> 
> - **Roll out to 1% of users, monitor, expand to 10%, monitor, expand to 100%.** Catches bad versions before they hit everyone.
> - **Cheaper** — no full duplicate environment.
> - **Reversible** — drain the canary if it's bad; users on the canary may have brief impact, but it's bounded.
> - **Better metrics** — the canary's behaviour is observable separately from the main fleet.
> 
> Canary requires:
> 
> - Traffic-routing infrastructure (service mesh, load balancer with weighted targeting).
> - Feature flags or version-aware code.
> - Observability per version.
> - Automated rollback triggers (error rate, latency).
> 
> Most modern teams use canary or some progressive delivery system (LaunchDarkly, Argo Rollouts, Flagger). Blue-green has become a niche.


- [ ] verdict: ______  (OK / DROP / FIX → ____)


---

## r09 — RELATIONAL

**Query:** what are the risks of running database migrations under load and how do I avoid them


**Gold 1:** `DatabaseMigrationStrategies.md` → `Locking under load`

> **Locking under load**
>
> Even "safe" operations interact poorly with long-running transactions:
> 
> - `ADD COLUMN` waits for all current transactions to finish before it can take its momentary lock.
> - A long-running `SELECT` can block a quick `ADD COLUMN` from acquiring its lock.
> - Other queries queue behind the waiting `ADD COLUMN`.
> 
> Result: a "fast" migration takes hours because of one transaction holding things open.
> 
> Defences:
> 
> - **`SET lock_timeout = '5s'` before the migration** — fail fast instead of holding everything up.
> - **Set `statement_timeout`** to prevent runaway migrations.
> - **Run during low-traffic windows** when possible, even for "safe" migrations.
> - **Kill long-running competitors** if needed (they probably shouldn't be running anyway).
> 
> Postgres-specific tooling: `pg_repack` for table rewrites without long locks; `pg_squeeze` for similar.


**Gold 2:** `DatabaseMigrationStrategies.md` → `The expand-contract pattern`

> **The expand-contract pattern**
>
> For any change that isn't a simple "add a column with a default":
> 
> ```
> 1. EXPAND  — Add the new structure. Old code still works.
> 2. MIGRATE — Backfill data. Application uses both old and new in parallel.
> 3. CONTRACT — Remove the old structure. Application uses only new.
> ```
> 
> Each phase is independently safe. The cost is more migrations and more code paths during transition; the benefit is no big-bang outages.
> 
> ### Example: rename a column
> 
> Bad: `ALTER TABLE users RENAME COLUMN phone TO phone_number;`
> 
> This is fast (no rewrite) but the application has the old name; deploys are fragile.
> 
> Better, expand-contract:
> 
> 1. **Expand**: `ALTER TABLE users ADD COLUMN phone_number TEXT;`. Application writes both `phone` and `phone_number`. Reads still use `phone`.
> 2. **Backfill**: `UPDATE users SET phone_number = phone WHERE phone_number IS NULL;` (in batches if needed).
> 3. **Migrate readers**: deploy code that reads from `phone_number`, falls back to `phone`. Then deploy code that reads only `phone_number`.
> 4. **Migrate writers**: deploy code that writes only to `phone_number`.
> 5. **Contract**: `ALTER TABLE users DROP COLUMN phone;`. Now safe — nobody uses it.
> 
> 5+ deploys instead of 1; zero do
> … (truncated)


- [ ] verdict: ______  (OK / DROP / FIX → ____)


---

## r10 — RELATIONAL

**Query:** how does graph RAG differ from standard RAG and what does it add


**Gold 1:** `GraphRAG.md` → `What graph RAG adds`

> **What graph RAG adds**
>
> Build a knowledge graph from your corpus:
> - Entities (nodes)
> - Relationships (edges)
> - Entity properties
> 
> Graph queries can:
> - Traverse relationships (find papers citing X cited by Y)
> - Aggregate (count, list)
> - Constrain by entity type or property
> 
> For multi-hop questions, graph traversal can find answers vector retrieval misses.


**Gold 2:** `GraphRAG.md` → `Architectures`

> **Architectures**
>
> ### Naive: graph-only retrieval
> 
> Extract entities from query; traverse graph; pass results to LLM.
> 
> Issues: brittle to entity extraction errors; misses passages without entities.
> 
> ### Hybrid: vector + graph
> 
> Vector retrieval for breadth; graph for relationships.
> 
> Combine results before LLM call.
> 
> ### Iterative: agent with graph tool
> 
> LLM agent uses graph queries as a tool. Decides when to traverse vs read.
> 
> Most flexible; highest cost.
> 
> ### Microsoft GraphRAG
> 
> Build hierarchical community summaries from graph. Use community summaries for global questions.
> 
> Specifically targets "what are the major themes" type queries.


- [ ] verdict: ______  (OK / DROP / FIX → ____)


---

## b01 — BOUNDARY

**Query:** how do I diagnose and fix a slow memory leak in a Java application


**Gold 1:** `JavaMemoryManagement.md` → `IV. Concrete Diagnostic Scenario: The "Slow Leak"`

> **IV. Concrete Diagnostic Scenario: The "Slow Leak"**
>
> **Problem:** Application throughput degrades over 48 hours. `jstat` shows the Old Generation is steadily climbing despite frequent GCs.
> 
> ### Step 1: Monitor Allocation vs. Reclamation
> ```bash


**Gold 2:** `JavaMemoryManagement.md` → `V. Critical Tuning Parameters`

> **V. Critical Tuning Parameters**
>
> 1.  **-Xms / -Xmx:** Always set these equal in production to prevent resizing pauses.
> 2.  **-XX:+UseContainerSupport:** Mandatory for Docker to ensure the JVM respects cgroup limits.
> 3.  **-XX:MaxDirectMemorySize:** Controls off-heap allocation (used by Netty/NIO). If not set, it defaults to `-Xmx`, which can lead to OS-level OOMs.
> 
> ---
> **See Also:**
> - [Performance Profiling](PerformanceProfiling) — Tools for memory analysis.
> - [Java Concurrency](JavaConcurrencyPatterns) — Impact of Virtual Threads on memory.
> - [Memory Architectures](MemoryArchitectures) — How JVM maps to physical RAM.


- [ ] verdict: ______  (OK / DROP / FIX → ____)


---

## b02 — BOUNDARY

**Query:** how do I handle stateful users and database compatibility during a canary rollout


**Gold 1:** `CanaryDeployments.md` → `3. Data Compatibility (The Hard Part)`

> **3. Data Compatibility (The Hard Part)**
>
> If Version 2 requires a database schema change, the canary (V2) and the stable pool (V1) must both function against the same DB.
> *   **Rule:** Database migrations must be **Additive and Backwards Compatible**. Never delete or rename columns until the rollout is 100% complete and the old version is decommissioned.


**Gold 2:** `CanaryDeployments.md` → `4. Sticky Canaries`

> **4. Sticky Canaries**
>
> To prevent a user from flipping between V1 and V2 (which can break UIs), use **Session Affinity**. 
> *   **Implementation:** Set a `canary-version` cookie on the first request. The API Gateway or Load Balancer respects this cookie to ensure the user stays on the same version throughout their session.
> 
> ---
> **See Also:**
> - [Auto Scaling Strategies](AutoScalingStrategies) — Handling the load shift.
> - [Backwards Compatibility Strategies](BackwardsCompatibilityStrategies) — Managing schema drift.
> - [Monitoring and Alerting](MonitoringAndAlerting) — The data source for ACA.


- [ ] verdict: ______  (OK / DROP / FIX → ____)


---

## b03 — BOUNDARY

**Query:** how do I run sprint planning from capacity estimation to sprint commitment


**Gold 1:** `SprintPlanning.md` → `I. Capacity Modeling`

> **I. Capacity Modeling**
>
> Capacity is the total amount of time the team can spend on sprint work, accounting for "Focus Factor."
> 
> ### A. The Capacity Formula
> $$
> \text{Available Capacity} = (\text{Total Dev Hours}) \times \text{Focus Factor}
> $$
> 
> 1.  **Total Dev Hours:** (Number of Devs$\times$Days in Sprint$\times$Hours per Day).2.  **Focus Factor ($F$):** A value between$0.6$and$0.8$representing the time spent on actual coding/testing after subtracting meetings, email, and context switching.
>     *   **Low F (0.4-0.5):** Heavy on-call rotation or fragmented meetings.
>     *   **High F (0.8-0.9):** Deep-work environment, minimal overhead.
> 
> ### B. Individual Capacity Worksheet (Example)
> *   **Dev A:** 10 days$\times$6 effective hrs = 60 hrs.
> *   **Dev B:** 8 days (2 days PTO)$\times$6 effective hrs = 48 hrs.
> *   **Total Team Capacity:** 108 hours.
> 
> ---


**Gold 2:** `SprintPlanning.md` → `III. The Planning Workflow`

> **III. The Planning Workflow**
>
> 1.  **Verify Definition of Ready (DoR):** Do the top stories have clear Acceptance Criteria?
> 2.  **Calculate Capacity:** Subtract PTO, holidays, and scheduled maintenance.
> 3.  **Select Stories:** Pull from the backlog based on priority until the sum of points$\approx$ Adjusted Velocity.
> 4.  **Task Out:** Break stories into sub-tasks (usually 2-6 hours each). If the sum of task hours > Available Capacity, the story must be removed.
> 
> ---


- [ ] verdict: ______  (OK / DROP / FIX → ____)


---

## b04 — BOUNDARY

**Query:** what happens to Wikantik search when the dense backend is unavailable


**Gold 1:** `HybridRetrieval.md` → `Wiring`

> **Wiring**
>
> The retrieval stack is assembled during engine init by `WikiEngine.wireHybridRetrieval()`:
> 
> 1. **EmbeddingConfig** — resolves backend + model from `wikantik.properties`. If the master flag is off, nothing else wires up.
> 2. **TextEmbeddingClient** — backend adapter (currently Ollama). Used by both index-time and query-time paths.
> 3. **EmbeddingIndexService** + **AsyncEmbeddingIndexListener** — hook into `ChunkProjector` so every page save re-embeds the new chunks.
> 4. **InMemoryChunkVectorIndex** — loads the embeddings table at construction and reloads after each batch so queries always see the latest corpus without hitting Postgres.
> 5. **QueryEmbedder** — wraps the client with a Caffeine cache, a timeout budget, and a hand-rolled circuit breaker (`CLOSED → HALF_OPEN → OPEN`).
> 6. **DenseRetriever** + **HybridFuser** + **HybridSearchService** — the query-time orchestrator. Registered as a manager so `SearchResource` can use it transparently.
> 7. **BootstrapEmbeddingIndexer** — one-shot state machine that backfills the embeddings table if it is empty for the current model. Runs async; exposed via the admin index-status panel.
> 8. **HybridMetricsBridge** — publishes embedder, bootstrap, a
> … (truncated)


**Gold 2:** `HybridRetrieval.md` → `Fail-closed behaviour`

> **Fail-closed behaviour**
>
> `HybridSearchService.rerank()` is the single choke point that guarantees BM25-only is always a valid fallback. Every abnormal path returns the input BM25 list unchanged:
> 
> | Trigger                                    | Result                                  |
> |--------------------------------------------|-----------------------------------------|
> | `wikantik.search.hybrid.enabled = false`   | BM25 verbatim                           |
> | Query null / blank                         | BM25 verbatim                           |
> | `QueryEmbedder.embed()` throws             | BM25 verbatim, WARN logged              |
> | Embedder returns `Optional.empty()`        | BM25 verbatim, DEBUG logged             |
> | `DenseRetriever.retrieve()` throws         | BM25 verbatim, WARN logged              |
> | Dense result set empty                     | BM25 verbatim                           |
> | Vector index not ready                     | BM25 verbatim (dense returns empty)     |
> | Circuit breaker OPEN                       | BM25 verbatim (embedder returns empty)  |
> 
> A fused response is a superset of BM25: pages that appeared only in dense results are appended after the fused block as `DenseOnlySearchRe
> … (truncated)


- [ ] verdict: ______  (OK / DROP / FIX → ____)


---

## b05 — BOUNDARY

**Query:** how does an agent maintain memory across multiple sessions


**Gold 1:** `AgentMemory.md` → `4. Long-Term Memory: Storage Selection`

> **4. Long-Term Memory: Storage Selection**
>
> Selecting the right substrate for long-term memory is critical for retrieval quality.
> 
> | Use Case | Substrate |
> |---|---|
> | **Semantic Recall** | Vector Database (Fuzzy match on past interactions) |
> | **User Preferences** | SQL Database (Typed columns for `timezone`, `persona`) |
> | **Exact Recall** | Transaction Log / Audit DB (e.g., "Was refund #123 issued?") |
> | **Relational Knowledge** | Knowledge Graph (Mapping entities and their relations) |
> 
> ### Forgetting and Retention
> Unbounded memory is an anti-pattern. Every channel needs a retention policy:
> *   **Privacy**: Implement a deletion path for GDPR/compliance (deleting specific user nodes/vectors).
> *   **TTL**: Apply Time-To-Live to facts that may become stale (e.g., `current_location`).


**Gold 2:** `AgentMemory.md` → `Cross-Session Continuity`

> **Cross-Session Continuity**
>
> A minimal continuity stack requires:
> 1.  **User Context**: Injected into every turn.
> 2.  **Session Summary**: The last $N$ turns of the previous session summarized and loaded at startup.
> 3.  **Preference Injection**: Structured facts ("prefers JSON output") placed in the system prompt.


- [ ] verdict: ______  (OK / DROP / FIX → ____)


---

## b06 — BOUNDARY

**Query:** how do I safely backfill data and roll back a large database migration


**Gold 1:** `DatabaseMigrationStrategies.md` → `Backfills at scale`

> **Backfills at scale**
>
> For large tables, backfilling all rows in one transaction is dangerous (locks, replication lag, transaction-id wraparound). Batch:
> 
> ```sql
> DO $$DECLARE
>     batch_size INT := 10000;
>     last_id BIGINT := 0;
> BEGIN
>     LOOP
>         UPDATE orders 
>         SET status = 'unknown' 
>         WHERE id > last_id AND status IS NULL
>         ORDER BY id LIMIT batch_size;
>         EXIT WHEN NOT FOUND;
>         last_id := (SELECT MAX(id) FROM orders WHERE status IS NULL);
>         PERFORM pg_sleep(0.1);  -- ease pressure
>     END LOOP;
> END$$;
> ```
> 
> For very large tables, use a job system (background worker) rather than SQL DO blocks — better observability and recoverability.


**Gold 2:** `DatabaseMigrationStrategies.md` → `Rollbacks`

> **Rollbacks**
>
> The classic question: "should migrations have down scripts?"
> 
> Pragmatic answer: **rarely useful**. By the time you'd run a down migration, the application has likely already deployed code expecting the new schema. The down migration becomes a fresh forward migration ("add the column back").
> 
> Rollback strategy that works: **don't migrate to changes you can't safely roll back from**. Use expand-contract; each phase is reversible by deploying older code.
> 
> Some tools support `down` migrations for development convenience (resetting a dev database). Treat those as dev-only; don't rely on them in production.


- [ ] verdict: ______  (OK / DROP / FIX → ____)


---

## b07 — BOUNDARY

**Query:** why was qwen3 chosen as the embedding model for the retrieval harness


**Gold 1:** `RetrievalExperimentHarness.md` → `7. Model selection — the 2026-04-18 decision`

> **7. Model selection — the 2026-04-18 decision**
>
> This is the run that picked `qwen3-embedding-0.6b` as the production
> embedding model. All three candidates indexed the same ~30k-chunk corpus,
> same BM25 baseline, same 40-query / 7-category ground truth, same
> max-score page aggregation at that point.
> 
> ### Raw results
> 
> | Model | dim | bm25 r@5 | dense r@5 | dense r@20 | dense MRR | hybrid r@5 | hybrid r@20 | hybrid MRR |
> |---|---|---|---|---|---|---|---|---|
> | `nomic-embed-v1.5` | 768 | 0.550 | 0.625 | 0.800 | 0.474 | 0.650 | 0.900 | 0.530 |
> | `bge-m3` | 1024 | 0.550 | 0.700 | 0.875 | 0.503 | 0.750 | 0.900 | 0.615 |
> | **`qwen3-embedding-0.6b`** | 1024 | 0.550 | **0.750** | **0.900** | 0.490 | **0.750** | **0.925** | 0.602 |
> 
> Reports on disk: `eval/report-nomic-embed-v1.5.txt`,
> `eval/report-bge-m3.txt`, `eval/report-qwen3-embedding-0.6b.txt`.
> 
> ### Decision rationale
> 
> - **qwen3 leads on recall at both cutoffs.** Dense recall@5 is a full
>   +0.050 over bge-m3 and +0.125 over nomic. Dense recall@20 is +0.025
>   over bge-m3 and +0.100 over nomic. Hybrid recall@20 (0.925) is the
>   best across the three.
> - **bge-m3 leads narrowly on MRR** (dense 0.503 vs 0.490; hybrid 0.615
>   vs 0.602). This was known, weighed, and accepted: for a RAG pipeli
> … (truncated)


- [ ] verdict: ______  (OK / DROP / FIX → ____)
