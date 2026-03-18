# Rebrand JSPWiki → Wikantik: Full Project Plan

## Context

This rebrand breaks from the legacy Apache JSPWiki identity to establish Wikantik as a system built for direct agent engagement and utility. The codebase has **~6,000+ occurrences** of "jspwiki"/"JSPWiki" across **~420 files** (753 Java sources, 23 POMs, 68 JSPs, 73 properties files, 81 JS files, and more). The rename touches every layer: Maven coordinates, Java packages, configuration keys, file names, UI assets, deployment configs, and documentation.

## Design Decisions

- **Package root**: `com.wikantik`
- **Class renames**: `JSPWiki*` → `Wikantik*` (e.g., `JSPWikiMarkupParser` → `WikantikMarkupParser`)
- **Backward compat**: No shim — clean break, only `wikantik.*` properties recognized
- **Parent POM**: Keep `org.apache:apache:35` for now (less disruption)

## Naming Conventions

| Vector | Old | New |
|--------|-----|-----|
| Application name | `JSPWiki` | `Wikantik` |
| Maven groupId | `org.apache.jspwiki` | `com.wikantik` |
| Java package root | `org.apache.wiki` | `com.wikantik` |
| Module directory prefix | `jspwiki-` | `wikantik-` |
| Config property prefix | `jspwiki.` | `wikantik.` |
| Config file names | `jspwiki.properties` / `jspwiki-custom.properties` | `wikantik.properties` / `wikantik-custom.properties` |
| Cache alias prefix | `jspwiki.` | `wikantik.` |
| Taglib URI | `http://jspwiki.apache.org/tags` | `https://wikantik.com/tags` |
| Docker service/volume | `jspwiki` / `jspwiki-data` | `wikantik` / `wikantik-data` |
| Database name/user | `jspwiki` | `wikantik` |
| Version | `3.1.1-SNAPSHOT` | `1.0.0-SNAPSHOT` |
| Class name prefix | `JSPWiki*` | `Wikantik*` |

---

## Codebase Scope Assessment

### Module Structure (22 modules, 23 pom.xml files)

| Module | Java Files | Purpose |
|--------|-----------|---------|
| `jspwiki-main` | 431 | Primary implementation |
| `jspwiki-mcp` | 71 | MCP server integration |
| `jspwiki-api` | 60 | Core interfaces and contracts |
| `jspwiki-util` | 41 | Utility classes |
| `jspwiki-markdown` | 37 | Markdown syntax support |
| `jspwiki-event` | 11 | Event system |
| `jspwiki-http` | 10 | HTTP filters/servlets |
| `jspwiki-bootstrap` | 7 | Initialization |
| `jspwiki-cache` | 4 | Caching abstraction |
| `jspwiki-war` | 0 | WAR packaging only |
| `jspwiki-it-tests` | (6 sub-modules) | Integration tests |
| `jspwiki-wikipages` | (4 language modules) | Wiki page content |
| `jspwiki-bom` | 0 | BOM (Bill of Materials) |

### Reference Counts by File Type

| File Type | Files with References | Approximate Occurrences |
|-----------|----------------------|------------------------|
| Java (.java) | 753 | ~3,000+ |
| Properties (.properties) | 23 | ~610 |
| XML (.xml) | 36 | ~316 |
| JSP (.jsp) | 68 | ~88 |
| JavaScript (.js) | 11 | ~30 |
| HTML (.html) | 22 | ~505 |
| CSS (.css) | 1 | ~1 |
| Documentation (.md) | 43 | ~1,031 |
| Docker/deployment | 5 | varies |
| **TOTAL** | **~420** | **~6,000+** |

### Critical Files

These files are central to the rebrand and will be touched in multiple phases:

- `jspwiki-api/src/main/java/org/apache/wiki/api/Release.java` — `APPNAME` constant, version numbers
- `jspwiki-util/src/main/java/org/apache/wiki/util/PropertyReader.java` — Config file name constants (`DEFAULT_JSPWIKI_CONFIG`, `CUSTOM_JSPWIKI_CONFIG`), governs how all configuration is loaded
- `jspwiki-api/src/main/java/org/apache/wiki/api/core/Engine.java` — 15 `PROP_*` constants defining the `jspwiki.*` property namespace
- `pom.xml` (root) — groupId, artifactId, module list, SCM URLs
- `jspwiki-main/src/main/resources/ini/jspwiki.properties` — Primary configuration file (219 `jspwiki.*` properties)
- `jspwiki-main/src/main/resources/META-INF/jspwiki.tld` — JSP tag library definition
- `jspwiki-cache/src/main/resources/ehcache-jspwiki.xml` — 7 cache alias names
- `jspwiki-main/src/test/java/org/apache/wiki/TestEngine.java` — Test infrastructure foundation

### Java Package Hierarchy (all under `org.apache.wiki`)

57 distinct package namespaces including: `api`, `ajax`, `attachment`, `auth` (acl/authorize/login/permissions/sso/user), `bootstrap`, `cache`, `content`, `diff`, `event`, `filters`, `forms`, `frontmatter`, `http.filter`, `i18n`, `its`, `management`, `markdown` (extensions/jspwikilinks/nodes/renderer/parser), `mcp` (completions/prompts/resources/tools), `modules`, `pages` (haddock), `parser` (markdown), `plugin`, `preferences`, `providers`, `references`, `render` (markdown), `search`, `spi`, `tags`, `ui` (admin/beans/progress), `url`, `util` (comparators), `variables`

### Branding Assets

- `jspwiki-war/src/main/webapp/images/jspwiki_logo.png`
- `jspwiki-war/src/main/webapp/images/jspwiki_logo_s.png`
- `jspwiki-war/src/main/webapp/images/jspwiki-icons.png`
- `jspwiki-war/src/main/webapp/images/jspwiki-strip.gif`

### Classes Requiring Rename (JSPWiki* → Wikantik*)

Found by searching for class names starting with `JSPWiki`:
- `JSPWikiMarkupParser` → `WikantikMarkupParser`
- `JSPWikiLinkNodePostProcessor` → `WikantikLinkNodePostProcessor`
- `JSPWikiLinkAttributeProvider` → `WikantikLinkAttributeProvider`
- `MarkdownForJSPWikiExtension` → `MarkdownForWikantikExtension`
- Any others found during implementation (grep for `class JSPWiki`)

### Sub-package Requiring Rename

- `org.apache.wiki.markdown.extensions.jspwikilinks` → `com.wikantik.markdown.extensions.wikilinks`

### Serialized Data

- `jspwiki-main/src/test/resources/wkflmgr.ser` — Java serialized workflow manager data with embedded `org.apache.wiki` class names. Must be deleted and regenerated after the package rename.

---

## Phase 0: Preparation (No Code Changes)

**Goal**: Safety net and baseline.

1. Tag current HEAD as `pre-rebrand-baseline`
2. Run full build and record passing test count: `mvn clean install -T 1C -DskipITs`
3. Run integration tests: `mvn clean install -Pintegration-tests`
4. Identify how `wkflmgr.ser` is generated (serialized workflow data with embedded class names — must be regenerated after package rename)
5. Inventory checklist of all files needing changes (completed — see scope assessment above)

**Verification**: No code changes; build is green.

---

## Phase 1: Identity Constants and Application Name
**Risk: Low | ~20 files**

Change user-visible application name without touching packages or coordinates.

### Changes

- `jspwiki-api/src/main/java/org/apache/wiki/api/Release.java:45` — `APPNAME = "JSPWiki"` → `"Wikantik"`, reset version constants to 1.0.0
- All `web.xml` files (5 locations) — `<display-name>` and `<description>`:
  - `jspwiki-war/src/main/webapp/WEB-INF/web.xml`
  - `jspwiki-main/src/test/resources/WEB-INF/web.xml`
  - `docker-files/web.xml`
  - Integration test web.xml files (check `jspwiki-it-tests/`)
- All `pom.xml` `<name>` and `<description>` elements (NOT coordinates yet) — 23 files
- Logger names: `getLogger("JSPWiki")` across JSP files

### Automation
- Manual edit for Release.java
- Scripted sed for pom.xml `<name>` and `<description>` elements
- Scripted sed for web.xml display-name and description
- Scripted sed for logger names

**Verification**: `mvn clean install -T 1C -DskipITs` passes. UI shows "Wikantik".

---

## Phase 2: Configuration Property Prefix (`jspwiki.` → `wikantik.`)
**Risk: HIGH | ~300+ files**

The highest-risk phase — properties are referenced everywhere. No backward-compat shim; clean break.

### 2a: Java Constants
- All `PROP_*` string constants changing `"jspwiki.xyz"` to `"wikantik.xyz"` across:
  - `Engine.java` — 15 PROP_ constants
  - Hundreds of Java files with `"jspwiki.` string literals
- **Automation**: `sed 's/"jspwiki\./"wikantik./g'` with exclusion for URLs like `jspwiki.apache.org`
- **CRITICAL**: The sed pattern must NOT match URLs. Use a two-pass approach:
  1. First pass: replace `"jspwiki.` with `"wikantik.` in Java string literals
  2. Review diff for false positives (URLs like `jspwiki.apache.org` in string literals)
  3. Manually revert any false positives

### 2b: Properties Files
- `jspwiki-main/src/main/resources/ini/jspwiki.properties` (219 occurrences)
- 4 test `jspwiki.properties` files:
  - `jspwiki-main/src/test/resources/ini/jspwiki.properties`
  - `jspwiki-api/src/test/resources/ini/jspwiki.properties`
  - `jspwiki-bootstrap/src/test/resources/ini/jspwiki.properties`
  - `jspwiki-util/src/test/resources/ini/jspwiki.properties`
- 4 `jspwiki-custom.properties` files:
  - `jspwiki-main/src/test/resources/jspwiki-custom.properties`
  - `jspwiki-util/src/test/resources/jspwiki-custom.properties`
  - `jspwiki-it-tests/jspwiki-selenide-tests/src/main/resources/jspwiki-custom.properties`
  - `tomcat/tomcat-11/lib/jspwiki-custom.properties`
- 3 `jspwiki-mcp.properties` files:
  - `jspwiki-mcp/src/main/resources/jspwiki-mcp.properties`
  - `jspwiki-mcp/src/test/resources/jspwiki-mcp.properties`
  - `jspwiki-it-tests/jspwiki-selenide-tests/src/main/resources/jspwiki-mcp.properties`
- `jspwiki-main/src/test/resources/jspwiki-vers-custom.properties`
- **Automation**: `sed 's/^jspwiki\./wikantik./g'` for property keys at line starts
- Also handle property values that reference `jspwiki.*` (e.g., provider class config comments)

### 2c: PropertyReader Constants
- `jspwiki-util/src/main/java/org/apache/wiki/util/PropertyReader.java`:
  - `DEFAULT_JSPWIKI_CONFIG` → `DEFAULT_WIKANTIK_CONFIG` (value: `"/ini/wikantik.properties"`)
  - `CUSTOM_JSPWIKI_CONFIG` → `CUSTOM_WIKANTIK_CONFIG` (value: `"/wikantik-custom.properties"`)
  - `PARAM_CUSTOMCONFIG` value: `"jspwiki.custom.config"` → `"wikantik.custom.config"`
  - `PARAM_CUSTOMCONFIG_CASCADEPREFIX` value: `"jspwiki.custom.cascade."` → `"wikantik.custom.cascade."`
- Update all callers:
  - `jspwiki-main/src/test/java/org/apache/wiki/TestEngine.java`
  - `jspwiki-util/src/test/java/org/apache/wiki/util/PropertyReaderTest.java`
  - `jspwiki-util/src/test/java/org/apache/wiki/util/MailUtilTest.java`
  - Any other files referencing these constants

### 2d: Cache Alias Names
- `jspwiki-cache/src/main/resources/ehcache-jspwiki.xml` — 7 cache aliases:
  - `jspwiki.renderingCache` → `wikantik.renderingCache`
  - `jspwiki.pageCache` → `wikantik.pageCache`
  - `jspwiki.pageTextCache` → `wikantik.pageTextCache`
  - `jspwiki.pageHistoryCache` → `wikantik.pageHistoryCache`
  - `jspwiki.attachmentsCache` → `wikantik.attachmentsCache`
  - `jspwiki.attachmentCollectionsCache` → `wikantik.attachmentCollectionsCache`
  - `jspwiki.dynamicAttachmentCache` → `wikantik.dynamicAttachmentCache`
- Also update test ehcache configs:
  - `jspwiki-cache/src/test/resources/ehcache-jspwiki-test.xml`
  - `jspwiki-main/src/test/resources/ehcache-jspwiki-small.xml`
- Java code referencing cache names by string (grep for these cache alias strings)

### 2e: Test Assertions
- All tests asserting property key names containing `jspwiki.`
- PropertyReaderTest has 57 references — needs careful update

**Verification**: `mvn clean install -T 1C -DskipITs` passes. Local deploy loads config correctly.

---

## Phase 3: Resource File Renames
**Risk: Medium | ~40 files**

Rename files whose names contain `jspwiki`. Use `git mv` to preserve history.

| Old | New | Count |
|-----|-----|-------|
| `ini/jspwiki.properties` | `ini/wikantik.properties` | ×4 (main + 3 test modules) |
| `jspwiki-custom.properties` | `wikantik-custom.properties` | ×4 |
| `jspwiki-mcp.properties` | `wikantik-mcp.properties` | ×3 |
| `jspwiki-vers-custom.properties` | `wikantik-vers-custom.properties` | ×1 |
| `jspwiki.tld` | `wikantik.tld` | ×2 (main + test) |
| `jspwiki.policy` | `wikantik.policy` | ×2 (main + test) |
| `jspwiki-testUserPolicy.policy` | `wikantik-testUserPolicy.policy` | ×1 |
| `ehcache-jspwiki.xml` | `ehcache-wikantik.xml` | ×1 |
| `ehcache-jspwiki-test.xml` | `ehcache-wikantik-test.xml` | ×1 |
| `ehcache-jspwiki-small.xml` | `ehcache-wikantik-small.xml` | ×1 |
| `jspwiki_module.xml` | `wikantik_module.xml` | ×3 (main + 2 test) |
| `jspwiki-mcp-instructions.txt` | `wikantik-mcp-instructions.txt` | ×1 |
| `jspwiki-checkstyle.xml` | `wikantik-checkstyle.xml` | ×2 |
| `jspwiki-eclipse-codestyle.xml` | `wikantik-eclipse-codestyle.xml` | ×2 |
| `jspwiki-container.policy` | `wikantik-container.policy` | ×1 |

### References to Update After Renames
- `PropertyReader.java` constants (already updated in Phase 2c, but verify string literals)
- `XmlUtilTest.java` — references `"ini/jspwiki_module.xml"`
- `DefaultEditorManager.java` — references `"ini/jspwiki_module.xml"`
- All pom.xml files referencing ehcache config file names
- `AuthorizationManager.java` — references `jspwiki.policy`
- All web.xml files referencing the TLD
- `McpConfig.java` — references `"jspwiki-mcp.properties"` and `"jspwiki-mcp-instructions.txt"`

**Verification**: `mvn clean install -T 1C -DskipITs` passes. TLD, policy, ehcache, module discovery all work.

---

## Phase 4: JSP Taglib URI
**Risk: Low | ~70 files**

- `wikantik.tld` (renamed in Phase 3): update `<uri>` to `https://wikantik.com/tags`, `<short-name>` to `wikantik`
- 67 JSP files: update `<%@ taglib uri="http://jspwiki.apache.org/tags" prefix="wiki" %>` — change URI, keep `prefix="wiki"` unchanged
- **Automation**: Single `sed` across all `.jsp` files, must be done atomically with TLD update since URI must match

**Verification**: `mvn clean install -T 1C -DskipITs`. Deploy and load JSP pages.

---

## Phase 5: Module Directory Renames + Maven Coordinates
**Risk: HIGH | Must be one commit**

These two must happen together because Maven `<module>` elements reference directory names.

### Directory renames (19 directories via `git mv`):

**Top-level modules (13):**
- `jspwiki-api` → `wikantik-api`
- `jspwiki-bom` → `wikantik-bom`
- `jspwiki-bootstrap` → `wikantik-bootstrap`
- `jspwiki-cache` → `wikantik-cache`
- `jspwiki-event` → `wikantik-event`
- `jspwiki-http` → `wikantik-http`
- `jspwiki-it-tests` → `wikantik-it-tests`
- `jspwiki-main` → `wikantik-main`
- `jspwiki-markdown` → `wikantik-markdown`
- `jspwiki-mcp` → `wikantik-mcp`
- `jspwiki-util` → `wikantik-util`
- `jspwiki-war` → `wikantik-war`
- `jspwiki-wikipages` → `wikantik-wikipages`

**IT sub-modules (6):**
- `jspwiki-it-test-custom` → `wikantik-it-test-custom`
- `jspwiki-it-test-cma` → `wikantik-it-test-cma`
- `jspwiki-it-test-cma-jdbc` → `wikantik-it-test-cma-jdbc`
- `jspwiki-it-test-custom-jdbc` → `wikantik-it-test-custom-jdbc`
- `jspwiki-it-test-custom-absolute-urls` → `wikantik-it-test-custom-absolute-urls`
- `jspwiki-selenide-tests` → `wikantik-selenide-tests`

**Content directory:**
- `docs/jspwiki-pages` → `docs/wikantik-pages`

### Maven coordinate changes (23 pom.xml files):
- Root pom.xml: `groupId` → `com.wikantik`, `artifactId` → `wikantik-builder`
- All modules: artifactIds `jspwiki-*` → `wikantik-*`
- All inter-module `<dependency>` groupId and artifactId references
- All `<module>` elements to match new directory names
- SCM URLs: update to new repository URL
- Project URL: update to new domain
- Keep Apache parent POM (`org.apache:apache:35`)

### Also update:
- `deploy-local.sh` — references to WAR file name (`JSPWiki.war`)
- `Dockerfile` — module references
- `CLAUDE.md`, `GEMINI.md` — module path references
- Tomcat config pointing to `docs/jspwiki-pages/`

**Verification**: Delete `~/.m2/repository/com/wikantik` (clean slate), then `mvn clean install -T 1C -DskipITs`.

---

## Phase 6: Java Package Rename (`org.apache.wiki` → `com.wikantik`)
**Risk: HIGHEST | ~750 Java files + ~50 config files**

The largest single phase. Touches every Java file in the project.

### Step-by-step:

1. **Move source directories** (in every module, both main and test):
   ```bash
   # For each module:
   git mv src/main/java/org/apache/wiki/ src/main/java/com/wikantik/
   git mv src/test/java/org/apache/wiki/ src/test/java/com/wikantik/
   ```
   Remove empty `org/apache/` parent directories afterward.

2. **Update package declarations and imports** (all ~750 Java files):
   ```bash
   sed -i 's/package org\.apache\.wiki/package com.wikantik/g' **/*.java
   sed -i 's/import org\.apache\.wiki/import com.wikantik/g' **/*.java
   ```

3. **Update FQCNs in non-Java files**:
   - `.tld` `<tag-class>` elements (28 tag classes)
   - `.policy` permission grants (`org.apache.wiki.auth.*`)
   - `web.xml` filter/servlet class names (`org.apache.wiki.http.filter.*`)
   - `wikantik_module.xml` class references
   - Properties files referencing provider class names (e.g., `org.apache.wiki.providers.FileSystemProvider`)
   - SPI service files in `META-INF/services/` (fully-qualified interface names as file names AND content)

4. **Rename JSPWiki* classes** (use `git mv` for each):
   - `JSPWikiMarkupParser.java` → `WikantikMarkupParser.java`
   - `JSPWikiLinkNodePostProcessor.java` → `WikantikLinkNodePostProcessor.java`
   - `JSPWikiLinkAttributeProvider.java` → `WikantikLinkAttributeProvider.java`
   - `MarkdownForJSPWikiExtension.java` → `MarkdownForWikantikExtension.java`
   - All other classes with `JSPWiki` in the name (grep for `class JSPWiki`)
   - Update all references to these class names across the codebase

5. **Rename sub-package** `jspwikilinks` → `wikilinks`:
   - `git mv` the directory under markdown module
   - Update all imports and references

6. **Handle serialized data**:
   - Delete `jspwiki-main/src/test/resources/wkflmgr.ser`
   - Find the test that generates it and run it to regenerate with new class names
   - Or modify the test to not depend on pre-serialized data

7. **Update SPI service files** in `META-INF/services/`:
   - File names are fully-qualified interface names — rename files
   - File contents are fully-qualified implementation class names — update content

**Verification**: `mvn clean install -T 1C -DskipITs` then `mvn clean install -Pintegration-tests`.

---

## Phase 7: Visual Assets and UI Text
**Risk: Low | ~10 files**

- Create new Wikantik artwork to replace 4 logo/icon images:
  - `jspwiki_logo.png` → `wikantik_logo.png`
  - `jspwiki_logo_s.png` → `wikantik_logo_s.png`
  - `jspwiki-icons.png` → `wikantik-icons.png`
  - `jspwiki-strip.gif` → `wikantik-strip.gif`
- Update CSS references to image filenames (in templates, `haddock-dark.css`, etc.)
- Update JSP references to image filenames
- Sweep all JSP/HTML for remaining user-visible "JSPWiki" text and replace with "Wikantik"
- Update i18n resource bundles (`CoreResources.properties`, `CoreResources_es.properties`, `CoreResources_ru.properties`)
- Font: check `FontJspwiki/style.css` reference

**Verification**: Deploy locally, visual inspection of all major pages.

---

## Phase 8: Docker, Deployment, and Infrastructure
**Risk: Medium | ~15 files**

- `docker-compose.yml`: service name `jspwiki` → `wikantik`, volume `jspwiki-data` → `wikantik-data`
- `Dockerfile`: update module references, paths, environment variable names (`jspwiki_*` → `wikantik_*`)
- `deploy-local.sh`: WAR file names, directory references
- `tomcat/tomcat-11/lib/` config files: rename and update contents
- `tomcat/tomcat-11/conf/Catalina/localhost/ROOT.xml`: update JNDI datasource names if they reference `jspwiki`
- Log4j config (`docker-files/log4j2.properties`): log file names
- `.github/workflows/maven.yml`: update if it references module names
- **Document database migration**: existing databases named `jspwiki` need renaming, JDBC user `jspwiki` needs renaming

**Verification**: Docker build, local Tomcat deployment, verify startup logs are clean.

---

## Phase 9: Documentation and Cleanup
**Risk: Low | ~25 files**

- `README.md`: full rewrite for Wikantik identity
- `CLAUDE.md`: update all module paths, build commands, architecture references
- `GEMINI.md`: same treatment
- `ChangeLog.md`: add rebrand entry at top
- `NOTICE`: update project name references (keep Apache License 2.0)
- `LICENSE`: review — Apache License 2.0 is fine, just update any project-specific text
- All `docs/*.md` files (DockerDeployment, DevelopingWithPostgresql, OAuthImplementation, etc.)
- Apache RAT config: update expected license header text if changing copyright holder
- Update any remaining SCM URLs pointing to `apache/jspwiki`
- Add `UPGRADING.md` with migration guide for anyone upgrading from JSPWiki
- Final sweep: `grep -ri "jspwiki" --include="*.java" --include="*.properties" --include="*.xml" --include="*.jsp" --include="*.js" --include="*.css"` — should return zero hits (except historical ChangeLog entries)

**Verification**: Read-through review. Full build passes: `mvn clean install -T 1C -DskipITs`.

---

## Execution Summary

| Order | Phase | Risk | Est. Files | Commit Strategy |
|-------|-------|------|-----------|-----------------|
| 0 | Preparation | None | 0 | Tag only |
| 1 | App Name Constants | Low | ~20 | Single commit |
| 2 | Config Property Prefix | **High** | ~300 | Single commit |
| 3 | Resource File Renames | Medium | ~40 | Single commit |
| 4 | JSP Taglib URI | Low | ~70 | Single commit |
| 5 | Dirs + Maven Coords | **High** | ~40 | Single commit (atomic) |
| 6 | Java Package Rename | **Highest** | ~800 | Single commit (atomic) |
| 7 | Visual Assets | Low | ~10 | Single commit |
| 8 | Docker/Deployment | Medium | ~15 | Single commit |
| 9 | Documentation | Low | ~25 | Single commit |

Each phase ends with a green build. Phases 2, 5, and 6 are the high-risk phases requiring careful scripting and review.

---

## End-to-End Verification

After all phases complete:
1. `mvn clean install -T 1C -DskipITs` — all unit tests pass
2. `mvn clean install -Pintegration-tests` — all integration tests pass
3. Local Tomcat deployment — UI shows "Wikantik", all pages render correctly
4. `grep -ri "jspwiki" --include="*.java" --include="*.properties" --include="*.xml" --include="*.jsp"` — zero hits (except historical references in ChangeLog/comments)
5. Docker build and run — container starts cleanly
6. MCP server starts and responds correctly with new naming

---

## Risk Mitigation Notes

1. **Sed false positives**: URLs like `jspwiki.apache.org` and `jspwiki-wiki.apache.org` must not be caught by property-prefix sed patterns. Use anchored patterns (`^jspwiki\.` for properties, `"jspwiki\.` for Java strings) and review diffs carefully.

2. **Maven local repo**: After Phase 5, the old `org.apache.jspwiki` artifacts in `~/.m2/repository` will cause confusion. Clean them or use `-U` flag.

3. **Serialized data**: `wkflmgr.ser` contains Java-serialized objects with `org.apache.wiki` class names baked in. Must be regenerated, not just renamed.

4. **Integration test ports**: Integration tests use fixed ports. Never run with `-T` flag. After the rebrand, verify IT tests still bind correctly.

5. **Git history**: Using `git mv` preserves file history tracking. Do directory renames and file renames via `git mv`, not manual move + add.

6. **Atomic phases**: Phases 5 and 6 cannot be partially applied — the build will break mid-phase. Plan for uninterrupted execution of these phases.
