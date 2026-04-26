# Gemini Development Guide: Tactical Refactoring & Feature Additions

This guide orients Gemini to the Wikantik codebase for the specific purposes of **tactical quality refactoring** and **minor feature additions**. 

### 1. Authoritative Rules (Via CLAUDE.md)
*   **TDD First:** Always write or update a test before fixing a bug or refactoring.
*   **Direct to Main:** Work directly on `main`; no PRs or feature branches.
*   **No Silent Failures:** Never swallow exceptions. Use `LOG.warn()` with context.
*   **Token Efficiency:** Use targeted reads (`start_line`/`end_line`) and `grep_search` to minimize context waste.
*   **Verification:** A task is incomplete until `mvn compile` (module-specific) and relevant tests pass.

---

### 2. Tactical Refactoring Guidelines
When performing refactoring (e.g., decoupling `WikiEngine` or modernizing legacy JSPWiki code):

*   **Follow the Guice Migration:** Refer to `docs/ArchitectureCritique.md`. Prioritize moving "Leaf" managers (I18n, Variable, Progress) to the hybrid DI bridge before tackling core managers.
*   **Interface Over Implementation:** Always code against the interfaces in `wikantik-api`, not the implementations in `wikantik-main`.
*   **Clean Up Legacy "Junk":** Replace legacy `TextUtil` or manual string manipulation with modern Java 21 features (`String.formatted()`, `Record` classes, or the `java.nio.file` API) where it improves readability.
*   **Maintain Semantic Stability:** Ensure that refactors do not break `canonical_id` stability or frontmatter parsing logic.

---

### 3. Minor Feature Addition Workflow
For adding small features like new Plugins, Filters, or REST endpoints:

*   **New Plugins:** Implement `com.wikantik.api.plugin.Plugin` and add the package to `jspwiki.plugin.searchPath` in `wikantik-custom.properties`.
*   **New Filters:** Implement `com.wikantik.api.filters.PageFilter`. Note that `StructuralSpinePageFilter` and `RunbookValidationPageFilter` are critical—do not interfere with their priority levels.
*   **New MCP Tools:** Add to `com.wikantik.mcp.McpToolRegistry` (Admin) or `com.wikantik.knowledge.mcp` (Knowledge). Tool names MUST be `snake_case`.
*   **Small REST Endpoints:** Extend `RestServletBase` to inherit ACL and policy grant enforcement.

---

### 4. Build & Verification (Tactical Loop)
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

### 5. Gemini Performance Tips
*   **Ask for Context:** If a refactor involves a manager you haven't seen yet, ask me to grep for its usage in `WikiEngine` or `WikiContext` first.
*   **Reasoning Over Code:** I excel at identifying patterns (like the "Service Locator" anti-pattern). Ask for an architectural opinion before I start editing large files.
*   **Surgical Edits:** Favor the `replace` tool for targeted changes over `write_file` to keep the diffs clean and understandable.
