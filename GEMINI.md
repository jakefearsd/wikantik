# Gemini Development Guide: Tactical Refactoring & Feature Additions

This guide orients Gemini to the Wikantik codebase for the primary purposes of **article writing**, **tactical quality refactoring** and **minor feature additions**. 

### 1. Authoritative Rules (Via CLAUDE.md)
*   **TDD First:** Always write or update a test before fixing a bug or refactoring.
*   **Direct to Main:** Work directly on `main`; no PRs or feature branches.
*   **No Silent Failures:** Never swallow exceptions. Use `LOG.warn()` with context.
*   **Token Efficiency:** Use targeted reads (`start_line`/`end_line`) and `grep_search` to minimize context waste.
*   **Verification:** A task is incomplete until `mvn compile` (module-specific) and relevant tests pass.

---

### 2. Article Authoring & Structural Spine
Writing content is a first-class engineering task in Wikantik. Every article must maintain the machine-readable "Structural Spine".

*   **Syntax Authority:** Refer to `docs/wikantik-pages/TextFormattingRules.md`. Use CommonMark with Flexmark extensions.
*   **Frontmatter is Mandatory:** Every page must start with a valid YAML block containing `title`, `type`, `cluster`, `status`, `date`, and `summary`.
*   **Canonical IDs:** The `canonical_id` (ULID) is stable for the life of the page and is the only authoritative linking mechanism. It is auto-injected by `StructuralSpinePageFilter` on first save. **Never modify or delete it.**
*   **Runbooks:** Pages of `type: runbook` must include a fully populated `runbook:` block (see `TextFormattingRules.md` for schema).
*   **Verification Metadata:** Use the `mark_page_verified` MCP tool to stamp `verified_at` and `verified_by`.
*   **KG Inclusion:** Use `kg_include: true|false` to override cluster-level knowledge graph extraction policy.

---

### 3. Tactical Refactoring Guidelines
When performing refactoring (e.g., decoupling `WikiEngine` or modernizing legacy JSPWiki code):

*   **Follow the Guice Migration:** Refer to `docs/ArchitectureCritique.md`. Prioritize moving "Leaf" managers (I18n, Variable, Progress) to the hybrid DI bridge before tackling core managers.
*   **Interface Over Implementation:** Always code against the interfaces in `wikantik-api`, not the implementations in `wikantik-main`.
*   **Clean Up Legacy "Junk":** Replace legacy `TextUtil` or manual string manipulation with modern Java 21 features (`String.formatted()`, `Record` classes, or the `java.nio.file` API) where it improves readability.
*   **Maintain Semantic Stability:** Ensure that refactors do not break `canonical_id` stability or frontmatter parsing logic.

---

### 4. Minor Feature Addition Workflow
For adding small features like new Plugins, Filters, or REST endpoints:

*   **New Plugins:** Implement `com.wikantik.api.plugin.Plugin` and add the package to `jspwiki.plugin.searchPath` in `wikantik-custom.properties`.
*   **New Filters:** Implement `com.wikantik.api.filters.PageFilter`. Note that `StructuralSpinePageFilter` and `RunbookValidationPageFilter` are critical—do not interfere with their priority levels.
*   **New MCP Tools:** Add to `com.wikantik.mcp.McpToolRegistry` (Admin) or `com.wikantik.knowledge.mcp` (Knowledge). Tool names MUST be `snake_case`.
*   **Small REST Endpoints:** Extend `RestServletBase` to inherit ACL and policy grant enforcement.

---

### 5. Build & Verification (Tactical Loop)
To keep the feedback loop fast and avoid massive context builds:

```bash
# 1. Compile only the affected module (e.g., wikantik-main)
mvn compile -pl wikantik-main -am -q

# 2. Run only the specific test affected
mvn test -pl wikantik-main -Dtest=MyNewFeatureTest

# 3. Verify the Structural Spine hasn't regressed (if changing content logic)
mvn test -pl wikantik-it-tests -Dtest=MainPageRegressionTest -Pintegration-tests

# 4. Final tactical deploy
bin/deploy-local.sh
```

---

### 6. Gemini Performance Tips
*   **Ask for Context:** If a refactor involves a manager you haven't seen yet, ask me to grep for its usage in `WikiEngine` or `WikiContext` first.
*   **Reasoning Over Code:** I excel at identifying patterns (like the "Service Locator" anti-pattern). Ask for an architectural opinion before I start editing large files.
*   **Surgical Edits:** Favor the `replace` tool for targeted changes over `write_file` to keep the diffs clean and understandable.
