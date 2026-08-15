# Dead Code Elimination Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove the dead code found by the 2026-08-15 audit — stale JSPWiki-era wiring descriptors, dead configuration keys, an almost-entirely-dead i18n bundle, dead `Command`/`ContextEnum` entries, unreferenced classes and methods, frontend leftovers, and WAR packaging relics — and put the two "dead-in-production but feature-shaped" subsystems (SpamFilter chain, memcached cache module) in front of the user for a keep/delete decision.

**Architecture:** Same outside-in discipline as the blog removal (`2026-08-05-blog-feature-removal.md`): each task deletes something nothing else references any more, the compiler + the affected unit suites are the safety net, and every task ends in a green build and a commit. Tasks are independent of each other except where stated, so they can be executed in any order and stopped after any task.

**Tech Stack:** Java 25 / Maven multi-module, JUnit 5, React 19 + Vite 8 + Vitest 4, Tomcat 11.

**Spec:** This plan *is* the audit record — the "Audit findings" section below is the spec.

## Global Constraints

- Work directly on `main`. No feature branches, no PRs.
- Never swallow exceptions with empty catch blocks — always log at least `LOG.warn()` with context.
- Long Maven runs go through `bin/agent-build.sh` — never a bare foreground `mvn`, never `nohup mvn -q … &`. `mvn compile` does **not** compile test sources — after any signature/arity change run `mvn test-compile -pl <module>`.
- Canonical gate before the final commit of prod code: `bin/run-tests.sh --parallel 4` (requires Docker). Verify against fresh `phase1-unit.log` / `it-parallel.log` mtimes only — `.test-suite-logs/` holds stale runs.
- Stage specific files by name. Never `git add -A`. Commit messages 1–3 lines.
- **No cruft.** No "formerly X" comments, no compatibility shims, no tests whose only job is to assert a thing is gone. Any test written as a removal anchor is deleted in the same task once the removal lands.
- Frontend checks: `cd wikantik-frontend && npm run lint && npm test` (lint must be 0 errors).
- No `News.md` updates.
- Nothing in this plan touches page content or the prod page store.

---

## Audit findings (2026-08-15)

### Blog feature — already gone; verified

The blog removal plan (`docs/superpowers/plans/2026-08-05-blog-feature-removal.md`) executed in full on
2026-08-05 (commits `bd125184d3` … `c9c4499ad3`). Re-verified today:

- No `com.wikantik.blog` package, no `BlogResource`, no `isBlogPage`/`normaliseBlogName`, no
  `weblogentryplugin.*`/`blog.*` i18n keys, no `WeblogPlugin` CSS marker anywhere in the tree.
- Every remaining `blog` hit in the repo is a false positive: prose in corpus pages (FIRE bloggers, SSR
  blogs, `blogspace.com` RSS URL, external `…/blog/…` links), `connectorGuides.js` copy, the
  `BriefingLogService blog` local variable, `News.md` history, and the PMD-baseline comment that
  *explains* the entries were removed.
- **Prod (Task 8 of that plan):** enumerated all 1,351 pages on wiki.wikantik.com over the knowledge
  MCP; zero pages under a `blog/` prefix. (`BloggerAgentWorkflow`, `RollerBlogPlatform`,
  `TheLocalWikiBloggerSites` are ordinary content pages *about* blogging.)

**No blog work remains.** Everything below is the *rest* of the dead-code sweep.

### Method

Four independent lenses, all mechanical and repeatable (scripts lived in the session scratchpad;
each finding below was hand-verified with a targeted grep before it was listed):

1. **Class-level:** every top-level type under `*/src/main/java` whose simple name appears in no other
   non-test source/config file (`.java .xml .properties .js .jsx .sh .yml .policy .json`) — then
   checked against reflective loading (`wikantik_module.xml`, `classmappings.xml`, `META-INF/services`,
   `web.xml`, shipped wiki pages that invoke plugins by name).
2. **Method-level:** token index over the whole tree; a method declared in main whose name has zero
   non-declaration occurrences in main. Framework overrides (JAAS, servlet, Flexmark, Lucene,
   `FileVisitor`, `HttpSessionListener`, `Authenticator`), reflection-invoked variables
   (`DefaultVariableManager.getXxx` = `{$xxx}`), and explicit test seams (`*ForTest`, `resetBlocklist`)
   were filtered out by hand.
3. **Config/descriptor-level:** every key in `ini/wikantik.properties` and every FQCN named in any
   `src/main` XML/properties file, checked for a reader / an existing class. Every i18n key in the three
   Java bundles checked for a literal reference.
4. **Frontend:** `knip` (unused files / deps / exports).

Plus targeted checks: DB tables in migrations vs. Java readers (all live), `filters.xml` shipping
(none — see SpamFilter), Maven module consumption (memcached).

### What is dead — by tier

**Tier A — certainly dead, delete (Tasks 1–8).**

| Area | What | Size |
|---|---|---|
| Stale descriptors | `classmappings.xml` maps 4 interfaces to classes that no longer exist (`RSSGenerator`, `EditorManager`, `TemplateManager`, `AdminBeanManager`); `wikantik_module.xml` `<editor name="plain">` block + `<adminBean>` naming a deleted class; `WikiModuleInfo.adminBeanClass` + 7 JSP-era module-metadata getters nothing reads; `log4j2.xml` logger for the deleted `com.wikantik.WikiServlet`; pom RAT exclude for a deleted Silk-icon readme; pom m2e `lifecycle-mapping` stub for the removed `yuicompressor` plugin | ~120 lines |
| Dead config keys | `wikantik.rss.*` (5 keys — `RSSGenerator` was deleted, no feed code exists), `wikantik.defaultprefs.*` (~50 keys — JSP UserPreferences skins/timeformats), `wikantik.securecookie`, `wikantik.cache.custom-config-file` — **no reader anywhere in Java** | ~130 lines |
| Dead i18n bundle | `templates/default{,_es,_ru}.properties` — 464 keys per locale of JSP template strings (`prefs.*`, `sbox.*`, `upload.*`, `view.*`, `login.*`, `edit.*` …). **Only the 4 `notification.createUserProfile.*` keys are read** (`DefaultUserManager`). Plus `TranslationsCheck` — a `main()` CLI whose only job was to compare these bundles | ~1,980 lines |
| Dead `Command`/`ContextEnum` entries | `PAGE_RSS`, `WIKI_INSTALL`, `WIKI_WORKFLOW`, `WIKI_MESSAGE` (+ `WikiContext.RSS/INSTALL/WORKFLOW/MESSAGE`); the whole *content-template* column (`ContextEnum.contentTemplate`, `Command.getContentTemplate()`, `GenericCommand.contentTemplate`, `WikiContext.getContentTemplate()`) — it named the JSP fragment to include and is read by nothing; the `WIKI_INSTALL` "filthy rotten hack" branch in `WikiContext.requiredPermission()`; `WikiServletFilter`'s "go to Install.jsp" HTML | ~80 lines |
| Unreferenced classes | `AuthorizerCallback`, `VersioningProvider` (interface, no implementor/caller), `WikiEventEmitter` (enum, test-only), `LinkCollector` (test-only), `FrontmatterPreloader` (called from a JSP scriptlet deleted in `78efda3e03`), `VariableContent` (test-only), `CleanTextRenderer` (test-only), `CommentedProperties` (test-only), `PropertiesUtils` (test-only), `MetricsPageProviderDecorator` (test-only; docs mention it as a *pattern example*), `SimpleMBean` (no subclass, no JMX registration), `MathSyntaxFixCli` (one-shot corpus fixer; job finished 2026-06-20), the empty `com.wikantik.ui.admin` package (`package.html` only) | ~1,300 lines + their tests |
| Unreferenced methods | `HubProposalRepository.clearCentroids`, `KgProposalRepository.countPendingProposals`, `TimedCounterList.getAddTime`, `LruPropertyCache.getMaxSize`, `WikiContext.getPageScope/getRenderingScope/getRequestScope`, `RequestScope.getVariableMap`, `PluginException.getRootThrowable`, `HumanComparator.getSortOrder`, `CsrfProtectionFilter.isCsrfProtectedPost`, `GenericCommand.getKind/isRedirectCommand`, `AbstractJDBCDatabase.lookupDataSource/testConnection`, `MarkupParser.pushBack`, `SessionEventDispatcher.sessionCount` | ~200 lines |
| Frontend | `scripts/math-probe/probe.mjs`; 4 unused `package.json` deps (`@codemirror/commands`, `@codemirror/language`, `dom-anchor-text-position`, `rehype-highlight`); 8 unused exports; 2 duplicate default+named exports | small |
| WAR/packaging relics | `WEB-INF/geronimo-web.xml`, `WEB-INF/jboss-deployment-structure.xml` (Tomcat is the only target); `src/main/config/dev/{OldChangeLog (543 KB), wikantik-checkstyle.xml, wikantik-eclipse-codestyle.xml}`; `src/main/config/doc/{APIChangesFrom2.4.html, aaa-diagram.pdf, wikantik-checkstyle.xml, wikantik-eclipse-codestyle.xml, LICENSE.SilkIconSet.txt, LICENSE.yui, LICENSE.cc-cod, LICENSE.cddl, LICENSE.cpl, LICENSE.ofl}` — licences for artifacts no longer shipped (`LICENSE.jdom`, `LICENSE.jaxen`, `LICENSE.flexmark`, `LICENSE.mit`, `LICENSE.mpl`, `LICENSE.akismet` stay: those deps are still on the classpath) | ~600 KB |

**Tier B — dead in production, but feature-shaped. Decided by the user 2026-08-15 (Task 9).**

| Candidate | Evidence | Decision |
|---|---|---|
| **SpamFilter chain** — `filters/SpamFilter` + `render/subsystem/spam/*` (12 classes, ~1,535 lines), `DefaultUserManager.validateSpamFilter`, the four `spam*` slots on `RenderingSubsystem.Services`, `RenderingSubsystemFactory.findSpamFilter`, the `akismet-kotlin` dependency (+ the `okio` CVE pin it drags in), `LICENSE.akismet`, tests `SpamFilterTest`, `SpamFilterCITest2`, `SpamSubsystemBranchTest`, `DefaultSpamExternalSignalsTest`, `DefaultSpamPatternMatcherTest` | Page filters are only instantiated from `filters.xml`; the only `filters.xml` in the repo is `wikantik-main/src/test/resources/`. **No deployment (local, docker1, cloud templates) ships one**, so `SpamFilter` is never constructed and the four `spam*` slots are always `null` in prod. The filter is also JSP-form-shaped (hidden hash/bot fields the React editor never sends) | **KEEP.** Retained deliberately as a dormant, re-activatable subsystem. Task 9 records *why* it is inert so the next audit does not re-flag it; `LICENSE.akismet` and the `okio` pin stay with it |
| **`wikantik-cache-memcached` module** (485 lines) | Built by the reactor and pulled into `wikantik-coverage-report` only. **Not a dependency of `wikantik-war`**, no `classmappings.xml` entry, no property selects it — it cannot be activated without hand-copying a jar into Tomcat's `lib`. Activation is documented in the class javadoc: override the `CachingManager` classmapping, put the jar on the classpath, set `wikantik.cache.memcached.servers` | **KEEP.** Opt-in drop-in adapter; Task 9 records the opt-in nature in the module list so it does not read as accidentally-orphaned |
| `AssignCanonicalIdsCli` (extract-cli) | One-shot `canonical_id` backfill; save-time `canonical_id` enforcement has been on since the Structural Spine shipped, so every page already has one | **DELETE** |
| `EmbeddingCli` | Ops diagnostic ("is this embedding model reachable / pullable"); referenced only from `docs/CodeQuality.md` | **KEEP** — runbook tool, not a feature |
| `ContextServiceBundleRetriever` (+ test) | Adapter for the *real-corpus* tier of `BundleEvalGateTest`, which is still a `TODO` in that test's javadoc; the live eval runs through the Python `bin/eval/*` scripts against `/api/bundle` | **DELETE** — YAGNI; the live evaluation path is `bin/eval/*.py` over `/api/bundle` |

**Tier C — audit-only follow-ups (not in this plan's tasks).**

- `CoreResources.properties`: 118 keys, 36 with a literal reference. The rest may be composed at
  runtime (`"security.error." + …`, `"common." + …`), so a literal grep is not proof. Needs a per-key
  trace before deleting; do it as a separate pass.
- `plugin/PluginResources.properties`: 33 keys, 15 literal references — same caveat.
- `mvn dependency:analyze` across the reactor for unused declared / undeclared used dependencies —
  not run in this audit (it needs a full compile); worth one dedicated session.
- `wikantik-war/src/main/config/dev/OldChangeLog` is excluded from RAT by name in the root pom
  (`pom.xml:1134`); Task 8 removes both the file and the exclude.

### Known false positives — do NOT touch

| Item | Why it looked dead |
|---|---|
| `IndexPlugin`, `UndefinedPagesPlugin`, `UnusedPagesPlugin`, `ReferredPagesPlugin` | Loaded reflectively by `[{IndexPlugin}]` etc. from shipped pages (`PageIndex.md`, `UndefinedPages.md`, `UnusedPages.md`) |
| `MarkdownSetupEngineLifecycleExtension` | Registered in `META-INF/services/com.wikantik.api.engine.EngineLifecycleExtension` |
| `DefaultVariableManager.getXxx()` | Reflection: `{$applicationname}` → `getApplicationname()` |
| `*.doOptions`, `contextInitialized`, `sessionCreated/Destroyed`, `preVisitDirectory/postVisitDirectory`, `removeEldestEntry`, `getKnnVectorsFormatForField`, `newSearcher`, `getPasswordAuthentication`, `affectsGlobalScope`/`finalizeBlock`/… in `InlineMathParser`, `abort` in `AbstractLoginModule` | Framework overrides |
| `resetForTest`, `setForTesting`, `forTesting`, `resetBlocklist`, `verifyCacheStats`, `inMemory` | Deliberate test seams |
| `wikantik.specialPage.*`, `wikantik.interWikiRef.*`, `wikantik.kg_policy.*`, `wikantik.bundle.reranker.*`, `appender.*`/`logger.*`/`rootLogger.*` | Read by prefix (`CommandResolver`, `Engine.getInterWikiURL`, `KgInclusionPolicy`, `RerankerConfig`) or by log4j itself |
| `wikantik-war/src/main/webapp/WEB-INF/wikantik.policy` | Still the documented file-based fallback for `policy_grants` |
| `WikiModuleInfo` itself, `wikantik_module.xml` `<plugin>`/`<filter>` blocks | Live: plugin + filter *metadata* registry |

---

## File Structure

**Deleted outright:**

```
wikantik-main/src/main/java/com/wikantik/          TranslationsCheck, LinkCollector
  auth/login/AuthorizerCallback
  providers/VersioningProvider, MetricsPageProviderDecorator
  frontmatter/FrontmatterPreloader
  parser/VariableContent
  render/CleanTextRenderer
  management/SimpleMBean  (whole package)
  ui/admin/package.html   (whole package)
  markdown/extensions/math/MathSyntaxFixCli
wikantik-main/src/main/resources/templates/         default.properties, default_es.properties, default_ru.properties
wikantik-event/src/main/java/com/wikantik/event/    WikiEventEmitter
wikantik-util/src/main/java/com/wikantik/util/      CommentedProperties, PropertiesUtils
tests                                               WikiEventEmitterTest, LinkCollectorTest, FrontmatterPreloaderTest,
                                                    CleanTextRendererTest, CommentedPropertiesTest, PropertiesUtilsTest,
                                                    MetricsPageProviderDecoratorTest
wikantik-frontend/scripts/math-probe/probe.mjs
wikantik-war/src/main/webapp/WEB-INF/               geronimo-web.xml, jboss-deployment-structure.xml
wikantik-war/src/main/config/dev/                   (whole dir)
wikantik-war/src/main/config/doc/                   APIChangesFrom2.4.html, aaa-diagram.pdf, wikantik-checkstyle.xml,
                                                    wikantik-eclipse-codestyle.xml, LICENSE.{SilkIconSet.txt,yui,cc-cod,cddl,cpl,ofl}
wikantik-extract-cli/src/main/java/com/wikantik/extractcli/   AssignCanonicalIdsCli (+2 tests)
wikantik-main/src/main/java/com/wikantik/knowledge/eval/      ContextServiceBundleRetriever (+test)
```

**Kept deliberately (user decision 2026-08-15):** the SpamFilter chain and `wikantik-cache-memcached`.
Task 9 documents why each is inert rather than deleting it.

**Modified:** `classmappings.xml`, `wikantik_module.xml`, `WikiModuleInfo.java` (+test), `log4j2.xml`,
root `pom.xml`, `ini/wikantik.properties`, `InternationalizationManager.java`, `DefaultUserManager.java`,
`CoreResources{,_es,_ru}.properties`, `InternationalizationManagerTest.java`, `ContextEnum.java`,
`Command.java`, `GenericCommand.java` (+test), `WikiContext.java` (+test), `WikiServletFilter.java`,
`ParserPackageBranchTest.java`, `PageProviderDecoratorTest.java`, `HubProposalRepository.java`,
`KgProposalRepository.java`, `TimedCounterList.java`, `LruPropertyCache.java`, `RequestScope.java`,
`PluginException.java`, `HumanComparator.java`, `CsrfProtectionFilter.java`,
`AbstractJDBCDatabase.java`, `MarkupParser.java`, `SessionEventDispatcher.java`, frontend
`package.json` + 8 source files, `docs/RefactorToPatterns.md`, `docs/wikantik-pages/RefactorToPatterns.md`,
`docs/CodeQuality.md`, `CHANGELOG.md`.

---

### Task 1: Purge stale JSPWiki-era wiring descriptors

**Files:**
- Modify: `wikantik-main/src/main/resources/ini/classmappings.xml:62-65,115-117,127-133`
- Modify: `wikantik-main/src/main/resources/ini/wikantik_module.xml:29-37`
- Modify: `wikantik-main/src/main/java/com/wikantik/modules/WikiModuleInfo.java` (`adminBeanClass` field, `el.getChildText("adminBean")`, `getAdminBeanClass()`, plus the other JSP-era getters that nothing reads: `getAuthorUrl`, `getHtmlTemplate`, `getModuleUrl`, `getModuleVersion`, `getScriptLocation`, `getStylesheetLocation` — and their backing fields / XML reads)
- Modify: `wikantik-main/src/test/java/com/wikantik/modules/WikiModuleInfoTest.java` (assertions on the removed getters — lines 57, 83, 103 for `getAdminBeanClass`, plus whichever lines assert the other six)
- Modify: `wikantik-war/src/main/config/tomcat/log4j2.xml:54-58`
- Modify: root `pom.xml` — RAT exclude at line 1139 (`SilkIconSet-readme.txt`); the `org.eclipse.m2e:lifecycle-mapping` `<plugin>` block in `<pluginManagement>` (starts ~line 1238; it only exists to silence Eclipse for `yuicompressor`/`antrun`/… executions — no other pom references `yuicompressor`)

**Interfaces:**
- Consumes: nothing
- Produces: `classmappings.xml` names only classes that exist; `WikiModuleInfo` exposes only `getName/getAuthor/getMinVersion/getMaxVersion/getResourceLocations` (whatever survives your read of the class — keep every getter that has a caller in `src/main`).

- [ ] **Step 1: Confirm which `WikiModuleInfo` getters have callers**

```bash
cd /home/jakefear/source/jspwiki
for m in getAdminBeanClass getAuthorUrl getHtmlTemplate getModuleUrl getModuleVersion getScriptLocation getStylesheetLocation getAuthor getName getMinVersion getMaxVersion; do
  printf "%-22s main=%s test=%s\n" $m \
    "$(grep -rlw $m --include=*.java wikantik-*/src/main | grep -v WikiModuleInfo.java | wc -l)" \
    "$(grep -rlw $m --include=*.java wikantik-*/src/test | wc -l)"
done
```

Expected: the first seven have `main=0`. Delete exactly those seven getters (and their fields + the `el.getChildText(...)` lines that populate them). Keep anything with `main>0`.

- [ ] **Step 2: Edit `classmappings.xml`**

Delete these three `<mapping>` blocks in full:

```xml
  <mapping>
    <requestedClass>com.wikantik.ui.admin.AdminBeanManager</requestedClass>
    <mappedClass>com.wikantik.ui.admin.DefaultAdminBeanManager</mappedClass>
  </mapping>
```
```xml
  <mapping>
    <requestedClass>com.wikantik.rss.RSSGenerator</requestedClass>
    <mappedClass>com.wikantik.rss.DefaultRSSGenerator</mappedClass>
  </mapping>
```
```xml
  <mapping>
    <requestedClass>com.wikantik.ui.EditorManager</requestedClass>
    <mappedClass>com.wikantik.ui.DefaultEditorManager</mappedClass>
  </mapping>
  <mapping>
    <requestedClass>com.wikantik.ui.TemplateManager</requestedClass>
    <mappedClass>com.wikantik.ui.DefaultTemplateManager</mappedClass>
  </mapping>
```

- [ ] **Step 3: Edit `wikantik_module.xml`**

Delete the `<editor name="plain">…</editor>` block (and the comment above it, "Define the editors that we ship with."). Nothing parses `/modules/editor` any more (`DefaultFilterManager` reads `/modules/filter`, `DefaultPluginManager` reads `/modules/plugin`).

- [ ] **Step 4: Edit `WikiModuleInfo` + its test**

Remove the seven fields/getters/XML reads from Step 1; remove the corresponding assertions from `WikiModuleInfoTest`. If a test method becomes empty, delete the method.

- [ ] **Step 5: Edit `log4j2.xml` and `pom.xml`**

Delete the `<!-- Page access tracking via WikiServlet -->` `<Logger name="com.wikantik.WikiServlet" …>` block. Delete the RAT exclude line for `SilkIconSet-readme.txt`. Delete the entire `org.eclipse.m2e` `lifecycle-mapping` `<plugin>` block from `<pluginManagement>`.

- [ ] **Step 6: Verify**

```bash
bin/agent-build.sh start t1 -- bash -c "mvn -q compile -pl wikantik-main && mvn test -pl wikantik-main -Dtest='WikiModuleInfoTest,ClassUtilTest,DefaultPluginManagerTest,DefaultFilterManagerTest,PluginCoverageTest' && mvn -q apache-rat:check"
bin/agent-build.sh wait t1 480
```

Expected: SUCCESS. (`ClassUtilTest` exercises `classmappings.xml`; the plugin/filter tests prove `wikantik_module.xml` still parses.)

- [ ] **Step 7: Commit**

```bash
git add wikantik-main/src/main/resources/ini/classmappings.xml wikantik-main/src/main/resources/ini/wikantik_module.xml \
        wikantik-main/src/main/java/com/wikantik/modules/WikiModuleInfo.java wikantik-main/src/test/java/com/wikantik/modules/WikiModuleInfoTest.java \
        wikantik-war/src/main/config/tomcat/log4j2.xml pom.xml
git commit -m "chore: purge stale JSPWiki-era descriptors (classmappings, editor module, WikiServlet logger, m2e stub)"
```

---

### Task 2: Delete dead configuration keys

**Files:**
- Modify: `wikantik-main/src/main/resources/ini/wikantik.properties` — `wikantik.cache.custom-config-file` (line 88 + its comment block), `wikantik.securecookie` (line 739 + comment), the RSS block (`wikantik.rss.generate` … `wikantik.rss.channelLanguage`, lines ~818–855 including the section banner), the `wikantik.defaultprefs.*` block (lines ~1035–1131 including the banner)

**Interfaces:**
- Consumes: nothing
- Produces: every `wikantik.*` key left in `wikantik.properties` has a reader (by literal or by prefix) in `src/main`.

- [ ] **Step 1: Prove each key is unread**

```bash
cd /home/jakefear/source/jspwiki
for k in "custom-config-file" "securecookie" "wikantik.rss" "defaultprefs"; do
  echo "$k: $(grep -rl "$k" --include=*.java --include=*.jsx --include=*.js --include=*.sh wikantik-*/src/main bin docker 2>/dev/null | wc -l)"
done
```

Expected: `0` for all four. If any is non-zero, that key is live — leave it and note it here.

- [ ] **Step 2: Delete the four blocks**

Remove each key together with the comment paragraph that introduces it, so no orphaned banner (`##### RSS ####`-style) survives. Do not renumber or reflow anything else in the file.

- [ ] **Step 3: Verify the properties still load and the docs don't reference the keys**

```bash
grep -rn "wikantik.rss\.\|defaultprefs\|securecookie\|custom-config-file" docs/*.md docs/wikantik-pages/*.md wikantik-war/src/main/config 2>/dev/null
bin/agent-build.sh start t2 -- mvn test -pl wikantik-main -Dtest='PropertyReaderTest,WikiEngineTest,PreferencesTest'
bin/agent-build.sh wait t2 420
```

Expected: the grep prints nothing (if it prints a doc line, delete that sentence too — it documents a knob that no longer exists); the build is SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add wikantik-main/src/main/resources/ini/wikantik.properties
git commit -m "chore: drop unread config keys (rss.*, defaultprefs.*, securecookie, cache.custom-config-file)"
```

---

### Task 3: Retire the JSP-template i18n bundle

The three `templates/default*.properties` bundles carry 464 keys per locale; exactly four
(`notification.createUserProfile.{accept,admin}.{subject,content}`) are read, all by
`DefaultUserManager`. Move those four into `CoreResources*` and delete the bundle, the
`DEF_TEMPLATE` constant, and `TranslationsCheck` (whose only purpose was to diff these files).

**Files:**
- Modify: `wikantik-main/src/main/resources/CoreResources.properties`, `CoreResources_es.properties`, `CoreResources_ru.properties` — append the 4 keys copied verbatim from the matching `templates/default*.properties`
- Modify: `wikantik-main/src/main/java/com/wikantik/auth/DefaultUserManager.java:270,276,297,299` — `InternationalizationManager.DEF_TEMPLATE` → `InternationalizationManager.CORE_BUNDLE`
- Modify: `wikantik-main/src/main/java/com/wikantik/i18n/InternationalizationManager.java:38` — delete `String DEF_TEMPLATE = "templates.default";` (and the commented-out `PLUGINS_BUNDLE` line 40 while you are there)
- Delete: `wikantik-main/src/main/resources/templates/` (whole directory)
- Delete: `wikantik-main/src/main/java/com/wikantik/TranslationsCheck.java`
- Modify: `wikantik-main/src/test/java/com/wikantik/i18n/InternationalizationManagerTest.java` — the test(s) that exercise `TranslationsCheck` / `DEF_TEMPLATE`
- Test: `wikantik-main/src/test/java/com/wikantik/auth/DefaultUserManagerTest.java` (or wherever profile-creation notification is covered — `grep -rl "createUserProfile" wikantik-main/src/test`)

**Interfaces:**
- Consumes: nothing
- Produces: `InternationalizationManager` exposes `CORE_BUNDLE` only; the four notification keys live in `CoreResources*`.

- [ ] **Step 1: Write the failing test**

Add to `InternationalizationManagerTest`:

```java
@Test
void notificationKeysLiveInCoreBundle() {
    final ResourceBundle rb = ResourceBundle.getBundle( InternationalizationManager.CORE_BUNDLE, Locale.ENGLISH );
    assertTrue( rb.containsKey( "notification.createUserProfile.accept.subject" ) );
    assertTrue( rb.containsKey( "notification.createUserProfile.accept.content" ) );
    assertTrue( rb.containsKey( "notification.createUserProfile.admin.subject" ) );
    assertTrue( rb.containsKey( "notification.createUserProfile.admin.content" ) );
}
```

This is a permanent test (it guards a real runtime lookup), not a removal anchor.

- [ ] **Step 2: Run to verify it fails**

```bash
bin/agent-build.sh start t3 -- mvn test -pl wikantik-main -Dtest=InternationalizationManagerTest
bin/agent-build.sh wait t3 420
```

Expected: FAIL — keys are not in `CoreResources` yet.

- [ ] **Step 3: Move the four keys**

For each locale (`""`, `_es`, `_ru`): copy the four `notification.createUserProfile.*` lines from `templates/default<locale>.properties` to the end of `CoreResources<locale>.properties`, under a comment `# Profile-creation e-mail notifications`. Keep the escapes (`\!`, `\:`, `\n`) exactly as they are.

- [ ] **Step 4: Repoint `DefaultUserManager`, delete `DEF_TEMPLATE`, delete the bundle + `TranslationsCheck`**

```bash
git rm -r -q wikantik-main/src/main/resources/templates
git rm -q wikantik-main/src/main/java/com/wikantik/TranslationsCheck.java
```

Then edit `DefaultUserManager` (4 sites) and `InternationalizationManager` (delete line 38 and 40). Remove the `TranslationsCheck` / `DEF_TEMPLATE` tests from `InternationalizationManagerTest`; if a test only asserted the templates bundle loads, delete it.

- [ ] **Step 5: Verify**

```bash
bin/agent-build.sh start t3b -- bash -c "mvn test-compile -pl wikantik-main && mvn test -pl wikantik-main -Dtest='InternationalizationManagerTest,DefaultUserManager*Test,UserManagerTest'"
bin/agent-build.sh wait t3b 480
grep -rn "templates.default\|DEF_TEMPLATE\|TranslationsCheck" --include=*.java --include=*.md --include=*.xml . | grep -v "/target/\|superpowers/plans"
```

Expected: PASS; grep prints nothing (fix any doc line it does print).

- [ ] **Step 6: Commit**

```bash
git add -u wikantik-main/
git commit -m "chore: retire JSP-template i18n bundle; move the 4 live notification keys into CoreResources"
```

---

### Task 4: Remove dead `Command`/`ContextEnum` entries and the content-template column

**Files:**
- Modify: `wikantik-api/src/main/java/com/wikantik/api/core/ContextEnum.java` — delete `PAGE_RSS`, `WIKI_INSTALL`, `WIKI_MESSAGE`, `WIKI_WORKFLOW`; drop the third constructor argument (`contentTemplate`) from every remaining constant, the field, the constructor, and `getContentTemplate()`
- Modify: `wikantik-api/src/main/java/com/wikantik/api/core/Command.java:78` — delete `String getContentTemplate();` and its javadoc
- Modify: `wikantik-main/src/main/java/com/wikantik/ui/GenericCommand.java` — delete constants `PAGE_RSS` (57), `WIKI_INSTALL` (69), `WIKI_MESSAGE` (72), `WIKI_WORKFLOW` (74) and their entries in the `ALL_COMMANDS`-style array (281–284); delete the `contentTemplate` field (90), constructor param (103), assignment (113), `getContentTemplate()` (155–157), and the `final String tmpl = ctx.getContentTemplate();` lines in the five factory helpers (298, 324, 346, 368, 390) plus wherever `tmpl` is passed on; also delete `getKind()` and `isRedirectCommand()` (zero callers)
- Modify: `wikantik-main/src/main/java/com/wikantik/WikiContext.java` — delete constants `INSTALL` (120), `MESSAGE` (129), `RSS` (147), `WORKFLOW` (159), `getContentTemplate()` (313–319), and the `WIKI_INSTALL` branch in `requiredPermission()` (743–752 — the "filthy rotten hack" comment goes with it; the method then falls straight through to its normal path)
- Modify: `wikantik-main/src/main/java/com/wikantik/ui/WikiServletFilter.java:~105-108` — the "Please go to the Install.jsp installer" HTML: replace with a plain `503` + one-line "Wikantik is not configured (wikantik.properties failed to load)" body; there is no installer
- Tests: `grep -rln "getContentTemplate\|PAGE_RSS\|WIKI_INSTALL\|WIKI_WORKFLOW\|WIKI_MESSAGE\|WikiContext.RSS\|WikiContext.INSTALL\|WikiContext.WORKFLOW\|WikiContext.MESSAGE\|isRedirectCommand\|getKind" wikantik-*/src/test wikantik-it-tests` — every hit is a test that must lose the assertion (or the whole method if that is all it did)

**Interfaces:**
- Consumes: nothing
- Produces: `Command` = `getRequestContext()`, `getURLPattern()`, `getName()`, `getTarget()`, `targetedCommand()`, `requiredPermission()` (unchanged); `ContextEnum` constructor is `(requestContext, urlPattern)`.

- [ ] **Step 1: Write the failing test**

Add to the existing `wikantik-main/src/test/java/com/wikantik/ui/GenericCommandTest.java` (or `CommandResolverTest` if there is no `GenericCommandTest`):

```java
@Test
void everyStaticCommandHasARequestContextAndUrlPattern() {
    for ( final Command c : GenericCommand.allCommands() ) {   // use whatever accessor exposes the static array
        assertNotNull( c.getRequestContext(), c.getName() );
        assertNotNull( c.getURLPattern(),     c.getName() );
    }
}
```

If no accessor exposes the array, iterate `ContextEnum.values()` instead and assert `getRequestContext()`/`getUrlPattern()` non-null. This is a permanent invariant test, not a removal anchor — it passes before *and* after; its job is to catch a botched constructor-arity edit in Step 3.

- [ ] **Step 2: Run it (expect PASS — baseline)**

```bash
bin/agent-build.sh start t4 -- mvn test -pl wikantik-main -Dtest='GenericCommandTest,CommandResolverTest,WikiContextTest'
bin/agent-build.sh wait t4 420
```

- [ ] **Step 3: Make the edits listed under Files**

Order: `ContextEnum` → `Command` → `GenericCommand` → `WikiContext` → `WikiServletFilter` → tests. Compile `wikantik-api` first (`mvn -q install -pl wikantik-api -DskipTests`) so `wikantik-main` sees the new interface.

- [ ] **Step 4: Verify**

```bash
bin/agent-build.sh start t4b -- bash -c "mvn -q install -pl wikantik-api -DskipTests && mvn test-compile -pl wikantik-main,wikantik-rest && mvn test -pl wikantik-main -Dtest='GenericCommandTest,CommandResolverTest,DefaultCommandResolverTest,WikiContextTest,WikiServletFilterTest,*URLConstructor*Test'"
bin/agent-build.sh wait t4b 540
grep -rn "getContentTemplate\|contentTemplate\|PAGE_RSS\|WIKI_INSTALL\|WIKI_WORKFLOW\|WIKI_MESSAGE\|Install.jsp" --include=*.java . | grep -v /target/
```

Expected: PASS; grep prints nothing.

- [ ] **Step 5: Commit**

```bash
git add -u wikantik-api/ wikantik-main/ wikantik-rest/ wikantik-it-tests/
git commit -m "chore: remove dead Command/ContextEnum entries (RSS, INSTALL, WORKFLOW, MESSAGE) and the JSP content-template column"
```

---

### Task 5: Delete unreferenced classes

**Files:**
- Delete (main): `wikantik-main/src/main/java/com/wikantik/auth/login/AuthorizerCallback.java`, `wikantik-main/src/main/java/com/wikantik/providers/VersioningProvider.java`, `wikantik-main/src/main/java/com/wikantik/providers/MetricsPageProviderDecorator.java`, `wikantik-main/src/main/java/com/wikantik/LinkCollector.java`, `wikantik-main/src/main/java/com/wikantik/frontmatter/FrontmatterPreloader.java`, `wikantik-main/src/main/java/com/wikantik/parser/VariableContent.java`, `wikantik-main/src/main/java/com/wikantik/render/CleanTextRenderer.java`, `wikantik-main/src/main/java/com/wikantik/management/` (dir), `wikantik-main/src/main/java/com/wikantik/ui/admin/` (dir), `wikantik-main/src/main/java/com/wikantik/markdown/extensions/math/MathSyntaxFixCli.java`, `wikantik-event/src/main/java/com/wikantik/event/WikiEventEmitter.java`, `wikantik-util/src/main/java/com/wikantik/util/CommentedProperties.java`, `wikantik-util/src/main/java/com/wikantik/util/PropertiesUtils.java`
- Delete (tests): `wikantik-event/src/test/java/com/wikantik/event/WikiEventEmitterTest.java`, `wikantik-main/src/test/java/com/wikantik/LinkCollectorTest.java`, `wikantik-main/src/test/java/com/wikantik/frontmatter/FrontmatterPreloaderTest.java`, `wikantik-main/src/test/java/com/wikantik/render/CleanTextRendererTest.java`, `wikantik-main/src/test/java/com/wikantik/providers/MetricsPageProviderDecoratorTest.java`, `wikantik-util/src/test/java/com/wikantik/util/CommentedPropertiesTest.java`, `wikantik-util/src/test/java/com/wikantik/util/PropertiesUtilsTest.java`
- Modify (tests that reference the deleted types incidentally): `wikantik-main/src/test/java/com/wikantik/parser/ParserPackageBranchTest.java` (`VariableContent`), `wikantik-main/src/test/java/com/wikantik/providers/PageProviderDecoratorTest.java` (`MetricsPageProviderDecorator`)
- Modify (docs): `docs/RefactorToPatterns.md`, `docs/wikantik-pages/RefactorToPatterns.md` (drop the `MetricsPageProviderDecorator` example — keep the *pattern* prose, cite `PageProviderDecorator` itself), `docs/CodeQuality.md` (only if it names `MathSyntaxFixCli`; it names `EmbeddingCli`, which stays), `CHANGELOG.md` (the `MathSyntaxFixCli` mention is history — leave it)
- Modify: `build-support/pmd-complexity-baseline.properties` — remove any line naming a deleted class (entries only ever come out; check with `grep -n "TranslationsCheck\|CommentedProperties\|PropertiesUtils\|MetricsPageProviderDecorator\|CleanTextRenderer\|FrontmatterPreloader\|LinkCollector\|SimpleMBean\|MathSyntaxFixCli\|VariableContent\|WikiEventEmitter\|AuthorizerCallback" build-support/pmd-complexity-baseline.properties`)

**Interfaces:**
- Consumes: Task 3 (which already deleted `TranslationsCheck`) — otherwise independent
- Produces: none of the deleted simple names appears anywhere in the tree except this plan and `CHANGELOG.md`.

- [ ] **Step 1: Re-prove each class is unreferenced (guards against drift since the audit)**

```bash
cd /home/jakefear/source/jspwiki
for c in AuthorizerCallback VersioningProvider MetricsPageProviderDecorator LinkCollector FrontmatterPreloader VariableContent CleanTextRenderer SimpleMBean MathSyntaxFixCli WikiEventEmitter CommentedProperties PropertiesUtils; do
  printf "%-30s %s\n" $c "$(grep -rlw $c --include=*.java --include=*.xml --include=*.properties --include=*.sh wikantik-*/src/main bin 2>/dev/null | grep -v "/$c.java" | tr '\n' ' ')"
done
```

Expected: every line ends empty. Any class that shows a referrer is live — drop it from this task.

- [ ] **Step 2: Delete**

```bash
git rm -q wikantik-main/src/main/java/com/wikantik/auth/login/AuthorizerCallback.java \
          wikantik-main/src/main/java/com/wikantik/providers/VersioningProvider.java \
          wikantik-main/src/main/java/com/wikantik/providers/MetricsPageProviderDecorator.java \
          wikantik-main/src/main/java/com/wikantik/LinkCollector.java \
          wikantik-main/src/main/java/com/wikantik/frontmatter/FrontmatterPreloader.java \
          wikantik-main/src/main/java/com/wikantik/parser/VariableContent.java \
          wikantik-main/src/main/java/com/wikantik/render/CleanTextRenderer.java \
          wikantik-main/src/main/java/com/wikantik/markdown/extensions/math/MathSyntaxFixCli.java \
          wikantik-event/src/main/java/com/wikantik/event/WikiEventEmitter.java \
          wikantik-util/src/main/java/com/wikantik/util/CommentedProperties.java \
          wikantik-util/src/main/java/com/wikantik/util/PropertiesUtils.java \
          wikantik-event/src/test/java/com/wikantik/event/WikiEventEmitterTest.java \
          wikantik-main/src/test/java/com/wikantik/LinkCollectorTest.java \
          wikantik-main/src/test/java/com/wikantik/frontmatter/FrontmatterPreloaderTest.java \
          wikantik-main/src/test/java/com/wikantik/render/CleanTextRendererTest.java \
          wikantik-main/src/test/java/com/wikantik/providers/MetricsPageProviderDecoratorTest.java \
          wikantik-util/src/test/java/com/wikantik/util/CommentedPropertiesTest.java \
          wikantik-util/src/test/java/com/wikantik/util/PropertiesUtilsTest.java
git rm -r -q wikantik-main/src/main/java/com/wikantik/management wikantik-main/src/main/java/com/wikantik/ui/admin
```

- [ ] **Step 3: Fix the two incidental test references and the docs**

`ParserPackageBranchTest`: delete the test method(s) that construct `VariableContent`. `PageProviderDecoratorTest`: if it uses `MetricsPageProviderDecorator` as its concrete subclass-under-test, replace it with a 5-line anonymous `PageProviderDecorator` subclass inside the test; if it merely lists it, delete the reference. Edit the two `RefactorToPatterns.md` files as described.

- [ ] **Step 4: Verify (all four modules, test-compile included)**

```bash
bin/agent-build.sh start t5 -- bash -c "mvn -q install -pl wikantik-util,wikantik-event -DskipTests && mvn test-compile -pl wikantik-main && mvn test -pl wikantik-util,wikantik-event && mvn test -pl wikantik-main -Dtest='ParserPackageBranchTest,PageProviderDecoratorTest,PluginCoverageTest,*Frontmatter*Test'"
bin/agent-build.sh wait t5 540
mvn -q pmd:check -Pcomplexity-gate
```

Expected: SUCCESS both.

- [ ] **Step 5: Commit**

```bash
git add -u wikantik-main/ wikantik-event/ wikantik-util/ docs/ build-support/
git commit -m "chore: delete 12 unreferenced classes (JSP-era helpers, test-only utilities, finished one-shot CLI)"
```

---

### Task 6: Delete unreferenced methods

Each of these has zero non-declaration occurrences in `src/main` and is not a framework override or a test seam. Where a *test* calls one, the test is testing dead code — delete that test method too.

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/knowledge/HubProposalRepository.java` — `clearCentroids`
- Modify: `wikantik-main/src/main/java/com/wikantik/knowledge/KgProposalRepository.java` — `countPendingProposals`
- Modify: `wikantik-util/src/main/java/com/wikantik/util/TimedCounterList.java` — `getAddTime`
- Modify: `wikantik-main/src/main/java/com/wikantik/providers/LruPropertyCache.java` — `getMaxSize`
- Modify: `wikantik-main/src/main/java/com/wikantik/WikiContext.java` — `getPageScope`, `getRenderingScope`, `getRequestScope` (**verify first**: if `WikiContext` implements them for an interface in `wikantik-api`, remove them from the interface too, or leave all three — do not leave a dangling interface method)
- Modify: `wikantik-main/src/main/java/com/wikantik/context/RequestScope.java` — `getVariableMap`
- Modify: `wikantik-api/src/main/java/com/wikantik/api/exceptions/PluginException.java` — `getRootThrowable`
- Modify: `wikantik-util/src/main/java/com/wikantik/util/comparators/HumanComparator.java` — `getSortOrder`
- Modify: `wikantik-http/src/main/java/com/wikantik/http/filter/CsrfProtectionFilter.java` — `isCsrfProtectedPost`
- Modify: `wikantik-main/src/main/java/com/wikantik/auth/AbstractJDBCDatabase.java` — `lookupDataSource`, `testConnection`
- Modify: `wikantik-main/src/main/java/com/wikantik/parser/MarkupParser.java` — `pushBack`
- Modify: `wikantik-main/src/main/java/com/wikantik/auth/SessionEventDispatcher.java` — `sessionCount`
- (`GenericCommand.getKind/isRedirectCommand` were removed in Task 4.)

**Interfaces:**
- Consumes: nothing
- Produces: none of the listed method names appears anywhere in `src/main` or `src/test`.

- [ ] **Step 1: Re-prove and locate**

```bash
cd /home/jakefear/source/jspwiki
for m in clearCentroids countPendingProposals getAddTime getMaxSize getPageScope getRenderingScope getRequestScope getVariableMap getRootThrowable getSortOrder isCsrfProtectedPost lookupDataSource testConnection pushBack sessionCount; do
  echo "== $m"; grep -rnw "$m" --include=*.java wikantik-*/src wikantik-it-tests 2>/dev/null | grep -v /target/ | cut -c1-140
done
```

Expected: for each name, only its declaration line (plus javadoc/`@link` lines in the same file, plus at most a test that calls it). Anything with a real caller in `src/main` is live — skip it. `getMaxSize`, `getSortOrder`, `getRequestScope` are generic names: read every hit, do not trust counts.

- [ ] **Step 2: Delete each method** (and any private helper that becomes unused, and any `@link` to it in the same file's javadoc; and any test method whose only purpose was to call it).

- [ ] **Step 3: Verify**

```bash
bin/agent-build.sh start t6 -- bash -c "mvn -q install -pl wikantik-api,wikantik-util -DskipTests && mvn test-compile -pl wikantik-main,wikantik-http,wikantik-rest && mvn test -pl wikantik-util,wikantik-http && mvn test -pl wikantik-main -Dtest='HubProposalRepository*Test,KgProposalRepository*Test,LruPropertyCacheTest,WikiContextTest,RequestScopeTest,AbstractJDBCDatabase*Test,MarkupParser*Test,SessionEventDispatcherTest'"
bin/agent-build.sh wait t6 540
```

Expected: SUCCESS. (Test class names are patterns — surefire ignores ones that don't exist.)

- [ ] **Step 4: Commit**

```bash
git add -u wikantik-api/ wikantik-util/ wikantik-http/ wikantik-main/
git commit -m "chore: delete 15 unreferenced methods"
```

---

### Task 7: Frontend leftovers

**Files:**
- Delete: `wikantik-frontend/scripts/math-probe/probe.mjs` (and the now-empty `scripts/` dir)
- Modify: `wikantik-frontend/package.json` — remove `@codemirror/commands`, `@codemirror/language`, `dom-anchor-text-position`, `rehype-highlight` from `dependencies` (all four: zero imports under `src/`)
- Modify: unused exports — `src/components/admin/MentionChunks.jsx` (`CHUNK_COMPONENTS`), `src/components/admin/table/index.js` (`useTableSelection`, `SelectionBar`, `BulkActionMenu`, `Pagination` re-exports), `src/components/frontmatter/schemaClient.js` (`_resetSchemaCache` — **check its tests first**: if a test imports it, it is a test seam; keep it), `src/components/pagegraph/filter-state.js` (`setClusters`), `src/components/pagegraph/filter-url.js` (`MANAGED_KEYS`, `PRESERVED_KEYS`), `src/hooks/useCapabilities.jsx` (`DEFAULT_CAPABILITIES`)
- Modify: duplicate exports — `src/hooks/useMentionPicker.js` and `src/utils/caretCoords.js` each export the same thing as both `default` and named; keep the named export, delete `export default`, and update the (few) importers that use the default form

**Interfaces:**
- Consumes: nothing
- Produces: `npx knip` reports zero unused files, deps, or exports (duplicate-exports section gone).

- [ ] **Step 1: Baseline**

```bash
cd /home/jakefear/source/jspwiki/wikantik-frontend && npx --yes knip@latest --no-progress --reporter compact
```

Expected: the exact list above (1 file, 4 deps, 8 exports, 2 duplicates). If knip now shows *more*, add those to this task; if it shows *fewer*, something was already fixed — skip it.

- [ ] **Step 2: Make the edits; then `npm install` to refresh `package-lock.json`**

- [ ] **Step 3: Verify**

```bash
npm run lint && npm test && npx --yes knip@latest --no-progress --reporter compact && npm run build
```

Expected: lint 0 errors, tests pass, knip prints nothing, Vite build succeeds (proves the removed deps were not needed transitively at bundle time — CodeMirror's `@uiw/react-codemirror` brings its own).

- [ ] **Step 4: Commit**

```bash
cd /home/jakefear/source/jspwiki
git add wikantik-frontend/package.json wikantik-frontend/package-lock.json wikantik-frontend/src
git rm -q wikantik-frontend/scripts/math-probe/probe.mjs
git commit -m "chore(frontend): drop 4 unused deps, dead probe script, unused/duplicate exports (knip clean)"
```

---

### Task 8: WAR packaging relics

**Files:**
- Delete: `wikantik-war/src/main/webapp/WEB-INF/geronimo-web.xml`, `wikantik-war/src/main/webapp/WEB-INF/jboss-deployment-structure.xml`
- Delete: `wikantik-war/src/main/config/dev/` (whole dir: `OldChangeLog`, `wikantik-checkstyle.xml`, `wikantik-eclipse-codestyle.xml`)
- Delete: `wikantik-war/src/main/config/doc/{APIChangesFrom2.4.html,aaa-diagram.pdf,wikantik-checkstyle.xml,wikantik-eclipse-codestyle.xml,LICENSE.SilkIconSet.txt,LICENSE.yui,LICENSE.cc-cod,LICENSE.cddl,LICENSE.cpl,LICENSE.ofl}`
- Keep: `config/doc/LICENSE.{jdom,jaxen,flexmark,mit,mpl,akismet}` (deps still on the classpath — `akismet-kotlin` stays because the SpamFilter chain is kept), `config/tomcat/*` (live templates), `config/wikantik-container.policy` (**check first**: `grep -rn container.policy bin docker docs/ProjectReference.md` — if nothing references it, delete it too)
- Modify: root `pom.xml:1134` — delete the `OldChangeLog` RAT exclude

**Interfaces:**
- Consumes: nothing
- Produces: `wikantik-war/src/main/config/` = `doc/` (live licences only) + `tomcat/`.

- [ ] **Step 1: Confirm nothing references the files**

```bash
cd /home/jakefear/source/jspwiki
grep -rn "geronimo\|jboss-deployment\|OldChangeLog\|APIChangesFrom2.4\|aaa-diagram\|checkstyle.xml\|eclipse-codestyle\|LICENSE\.\(SilkIconSet\|yui\|cc-cod\|cddl\|cpl\|ofl\)\|container.policy" \
  --include=*.xml --include=*.sh --include=*.md --include=Dockerfile* --include=*.yml . | grep -v "/target/\|superpowers/plans\|OldChangeLog:"
```

Expected: only `pom.xml:1134` (the RAT exclude) and `docs/full_rebrand_project.md` (a historical rename table — leave it). Anything else is a live reference; keep that file.

- [ ] **Step 2: Delete + edit pom**

```bash
git rm -q wikantik-war/src/main/webapp/WEB-INF/geronimo-web.xml wikantik-war/src/main/webapp/WEB-INF/jboss-deployment-structure.xml
git rm -r -q wikantik-war/src/main/config/dev
git rm -q wikantik-war/src/main/config/doc/{APIChangesFrom2.4.html,aaa-diagram.pdf,wikantik-checkstyle.xml,wikantik-eclipse-codestyle.xml,LICENSE.SilkIconSet.txt,LICENSE.yui,LICENSE.cc-cod,LICENSE.cddl,LICENSE.cpl,LICENSE.ofl}
```

Remove the `OldChangeLog` `<exclude>` line from `pom.xml`.

- [ ] **Step 3: Verify**

```bash
bin/agent-build.sh start t8 -- bash -c "mvn -q apache-rat:check && mvn -q package -pl wikantik-war -DskipTests"
bin/agent-build.sh wait t8 480
```

Expected: SUCCESS; the WAR builds without the two descriptors (Tomcat never read them).

- [ ] **Step 4: Commit**

```bash
git add -u wikantik-war/ pom.xml
git commit -m "chore(war): drop non-Tomcat deployment descriptors, JSPWiki OldChangeLog, and licences for unshipped artifacts"
```

---

### Task 9: Tier-B — delete the two finished adapters, document the two kept subsystems

**User decision, 2026-08-15:** the SpamFilter chain and `wikantik-cache-memcached` are **kept**;
`AssignCanonicalIdsCli` and `ContextServiceBundleRetriever` are **deleted**; `EmbeddingCli` is kept.

Keeping the first two is a deliberate choice, not an oversight — so this task also writes that fact
down where the *next* auditor will look. Without it, a future sweep re-derives "no caller, no
deployment ships it" and re-proposes deletion. **Do not** delete, deprecate, or otherwise weaken
either kept subsystem; do not remove `akismet-kotlin`, the `okio` pin, `LICENSE.akismet`, the
`<filter class="com.wikantik.filters.SpamFilter">` block, or the memcached module.

**Files:**
- Delete: `wikantik-extract-cli/src/main/java/com/wikantik/extractcli/AssignCanonicalIdsCli.java`, `wikantik-extract-cli/src/test/java/com/wikantik/extractcli/AssignCanonicalIdsCliTest.java`, `wikantik-extract-cli/src/test/java/com/wikantik/extractcli/AssignCanonicalIdsCliMainTest.java`
- Delete: `wikantik-main/src/main/java/com/wikantik/knowledge/eval/ContextServiceBundleRetriever.java`, `wikantik-main/src/test/java/com/wikantik/knowledge/eval/ContextServiceBundleRetrieverTest.java`
- Modify: `wikantik-main/src/test/java/com/wikantik/knowledge/eval/BundleEvalGateTest.java:~40-49` — the `<p><b>Real-corpus tier (TODO, Phase-0 Task 8 Step 4):</b> …` javadoc paragraph references `{@link ContextServiceBundleRetriever}`
- Modify: `wikantik-main/src/main/java/com/wikantik/filters/SpamFilter.java` — add the dormancy note to the class javadoc
- Modify: `CLAUDE.md` — the `wikantik-cache-memcached` bullet in "Module Structure"
- Check: `docs/superpowers/plans/2026-07-10-eval-harness-phase1.md`, `docs/superpowers/plans/2026-06-13-phase-0-bundle-eval-harness.md` mention `ContextServiceBundleRetriever` — these are historical plan records, **leave them alone**

**Interfaces:**
- Consumes: nothing
- Produces: `wikantik-extract-cli` exposes only the extractor + `IngestDocumentsCli` entry points; `com.wikantik.knowledge.eval` contains no live-service adapter.

- [ ] **Step 1: Confirm both deletions are safe**

```bash
cd /home/jakefear/source/jspwiki
grep -rn "AssignCanonicalIdsCli\|ContextServiceBundleRetriever" \
  --include=*.java --include=*.xml --include=*.sh --include=*.py --include=*.md . 2>/dev/null \
  | grep -v "/target/\|superpowers/plans/"
```

Expected: only the files listed above (the two classes, their tests, and the `BundleEvalGateTest`
javadoc). A hit in `bin/`, `eval/`, or a pom `<mainClass>` means something still drives it — stop and
report instead of deleting.

- [ ] **Step 2: Delete the four files**

```bash
git rm -q wikantik-extract-cli/src/main/java/com/wikantik/extractcli/AssignCanonicalIdsCli.java \
          wikantik-extract-cli/src/test/java/com/wikantik/extractcli/AssignCanonicalIdsCliTest.java \
          wikantik-extract-cli/src/test/java/com/wikantik/extractcli/AssignCanonicalIdsCliMainTest.java \
          wikantik-main/src/main/java/com/wikantik/knowledge/eval/ContextServiceBundleRetriever.java \
          wikantik-main/src/test/java/com/wikantik/knowledge/eval/ContextServiceBundleRetrieverTest.java
```

- [ ] **Step 3: Repoint the `BundleEvalGateTest` javadoc**

Replace the "Real-corpus tier (TODO …)" paragraph — which describes a JUnit tier that was never
built and names the class just deleted — with a statement of where real-corpus evaluation actually
happens:

```java
 * <p><b>Real-corpus evaluation</b> does not run here: it lives in {@code bin/eval/*.py}, which
 * drives {@code GET /api/bundle} against a deployed wiki and scores the results in
 * {@code eval/bundle-corpus/}. This class only asserts that the corpus file and its harness
 * plumbing are present and well-formed.</p>
```

Keep the rest of the javadoc and every test method as they are.

- [ ] **Step 4: Record why the SpamFilter chain is kept**

In `wikantik-main/src/main/java/com/wikantik/filters/SpamFilter.java`, add to the **end** of the
existing class javadoc (do not reflow or edit the rest of it):

```java
 *  <p><b>Activation.</b> Page filters are instantiated only from {@code filters.xml}
 *  ({@code DefaultFilterManager}); no shipped deployment includes one, so this filter is
 *  normally not constructed and the {@code spam*} slots on {@code RenderingSubsystem.Services}
 *  are null. That is intentional — the chain is retained as a dormant, re-activatable
 *  subsystem. Drop a {@code filters.xml} on the classpath (or set
 *  {@code wikantik.filterConfig}) to switch it on.</p>
```

- [ ] **Step 5: Record that memcached is opt-in**

In `CLAUDE.md`, change the module bullet:

```markdown
- **wikantik-cache-memcached**: Distributed cache adapter for Memcached
```

to:

```markdown
- **wikantik-cache-memcached**: Distributed cache adapter for Memcached. **Opt-in, not wired into the WAR** — to use it, override the `com.wikantik.cache.CachingManager` mapping in `classmappings.xml`, put this jar on the container classpath, and set `wikantik.cache.memcached.servers`. Retained deliberately for multi-node deployments; the default single-node path is EhCache (`wikantik-cache`).
```

- [ ] **Step 6: Verify**

```bash
bin/agent-build.sh start t9 -- bash -c "mvn test-compile -pl wikantik-extract-cli,wikantik-main && mvn test -pl wikantik-extract-cli && mvn test -pl wikantik-main -Dtest='BundleEvalGateTest,SpamFilterTest,SpamSubsystemBranchTest'"
bin/agent-build.sh wait t9 540
```

Expected: SUCCESS — including the SpamFilter suites, which prove the kept chain was not disturbed.

- [ ] **Step 7: Commit**

```bash
git add -u wikantik-extract-cli/ wikantik-main/ CLAUDE.md
git commit -m "chore: delete finished canonical-id backfill CLI and unused bundle-eval adapter

Document why the SpamFilter chain and memcached adapter are deliberately kept dormant."
```

---

### Task 10: Sweep, changelog, canonical gate

**Files:**
- Modify: `CHANGELOG.md` — `## [Unreleased]` → `### Removed`
- Modify: `CLAUDE.md` / `docs/ProjectReference.md` only if Task 9 removed a module

- [ ] **Step 1: Full-tree sweep for every deleted symbol**

```bash
cd /home/jakefear/source/jspwiki
grep -rn "TranslationsCheck\|CommentedProperties\|PropertiesUtils\|MetricsPageProviderDecorator\|CleanTextRenderer\|FrontmatterPreloader\|LinkCollector\|SimpleMBean\|MathSyntaxFixCli\|VariableContent\|WikiEventEmitter\|AuthorizerCallback\|VersioningProvider\b\|AssignCanonicalIdsCli\|ContextServiceBundleRetriever\|getContentTemplate\|DEF_TEMPLATE\|templates.default\|defaultprefs\|wikantik.rss\.\|securecookie\|DefaultRSSGenerator\|DefaultEditorManager\|DefaultTemplateManager\|AdminBeanManager\|PlainEditorAdminBean\|geronimo\|OldChangeLog\|math-probe" \
  --include=*.java --include=*.jsx --include=*.js --include=*.xml --include=*.properties --include=*.md --include=*.yml --include=*.sh . 2>/dev/null \
  | grep -v "/target/\|node_modules\|^./tomcat/\|superpowers/plans/\|CHANGELOG.md\|docs/full_rebrand_project.md"
```

Expected: no output. Anything printed is a leftover — remove it.

- [ ] **Step 2: Changelog**

```markdown
### Removed
- **Dead-code sweep (2026-08-15 audit).** Stale JSPWiki-era wiring (`classmappings.xml` entries for
  `RSSGenerator`/`EditorManager`/`TemplateManager`/`AdminBeanManager`, the `plain` editor module,
  `WikiModuleInfo` JSP metadata getters), unread config keys (`wikantik.rss.*`, `wikantik.defaultprefs.*`,
  `wikantik.securecookie`, `wikantik.cache.custom-config-file`), the JSP-template i18n bundle
  (`templates/default*.properties`, ~460 dead keys × 3 locales — the 4 live notification keys moved to
  `CoreResources`), the `PAGE_RSS`/`WIKI_INSTALL`/`WIKI_WORKFLOW`/`WIKI_MESSAGE` commands and the
  content-template column of `Command`/`ContextEnum`, 12 unreferenced classes and 15 unreferenced
  methods, the finished `AssignCanonicalIdsCli` backfill and the unused `ContextServiceBundleRetriever`
  eval adapter, non-Tomcat deployment descriptors and JSPWiki's `OldChangeLog`, and four unused
  frontend dependencies. The SpamFilter chain and the memcached cache adapter were reviewed and
  deliberately kept as dormant, re-activatable subsystems; both now document why they are inert.
```

- [ ] **Step 3: Canonical gate**

```bash
bin/agent-build.sh start gate-dead -- bin/run-tests.sh --parallel 4
bin/agent-build.sh wait gate-dead 540      # poll until it terminates
ls -la --time-style=+%H:%M:%S .test-suite-logs/phase1-unit.log .test-suite-logs/it-parallel.log
grep -E "RESULT" .build-logs/gate-dead.log
```

Expected: `RESULT: ALL PASSED`, both log mtimes inside this run's window. Expect the unit count to drop by roughly the number of deleted test methods (~80–120 — nine deleted test classes plus the individual methods removed in Tasks 1, 3, 4, 5 and 6); a *large* unexplained drop means tests stopped being discovered.

- [ ] **Step 4: Commit**

```bash
git add CHANGELOG.md
git commit -m "docs: changelog for dead-code sweep"
```

---

## Self-Review

**Spec coverage.** Every Tier-A row maps to a task: descriptors → T1; config keys → T2; i18n bundle + `TranslationsCheck` → T3; Command/ContextEnum + `WikiServletFilter` installer text + `GenericCommand.getKind/isRedirectCommand` → T4; classes → T5; methods → T6; frontend → T7; WAR relics → T8. Tier B → T9 (decisions taken 2026-08-15; two deletions + two documented keeps). Tier C is explicitly out of scope and listed as follow-ups. Sweep + gate → T10.

**Ordering.** Tasks 1–9 are independent and may run in any order, with one exception: T5 assumes T3 already deleted `TranslationsCheck` (T5's list omits it for that reason). T10 runs last.

**Known risks.** T4 changes an interface in `wikantik-api` (`Command.getContentTemplate` removed) — every module that implements `Command` must be recompiled; the plan installs `wikantik-api` first and `test-compile`s `wikantik-main` + `wikantik-rest`. T6 has three generically-named methods (`getMaxSize`, `getSortOrder`, `getRequestScope`) that must be read, not counted. T9 is now low-risk (two orphan deletions plus two doc edits); its main hazard is an over-eager executor extending the sweep to the kept SpamFilter/memcached code, which the task forbids explicitly.

**Naming consistency.** All symbol names in tasks were copied from the audit greps in this session; the sweep in T10 Step 1 uses the same spellings.
