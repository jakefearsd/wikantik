# PlantUML Rendered Fences Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** ` ```plantuml ` fences draw as server-rendered, sandboxed, scrubbed SVG (light and dark) in the reader, SSR, embeds and both editor previews, with diagram links counted as page references.

**Architecture:**
- A new isolated module `wikantik-plantuml` owns PlantUML. It is shaded, with Ditaa stripped. It provides the policy, the sandboxed engine, the SVG scrub and a bounded render service.
- `wikantik-main` changes in three ways:
  - The hook emits a signed placeholder at render time.
  - A `ServerFenceResolver` splices drawings in once, when the outermost `textToHTML` returns, so caches only ever hold placeholders.
  - `PlantUmlFenceRenderer` handles the parse-time half (check and links).
- A REST endpoint serves the editor previews, and the frontend decorates server-drawn figures and fetches previews.

**Tech Stack:** Java 25, `net.sourceforge.plantuml:plantuml-mit` (Smetana layout), maven-shade-plugin, Flexmark, OWASP sanitizer (unchanged except one inert attribute), React/Vite, vitest, Selenide.

**Spec:** `docs/superpowers/specs/2026-10-25-plantuml-fences-design.md` (read it; this plan argues from it).

## Global Constraints

**Licensing and dependency**
- No GPL or LGPL code in the WAR. The shipped jar must contain no `org/stathissideris/` entries, and the unshaded `plantuml-mit` jar must never reach `WEB-INF/lib`.
- Pin `net.sourceforge.plantuml:plantuml-mit` **1.2026.8**, published 2026-09-05. It is the newest release at least two weeks old; 1.2026.9 is too new. Re-verify the spike's findings on 1.2026.8 through the tests; don't assume them.
- Sandbox: `PLANTUML_SECURITY_PROFILE=SANDBOX`, set as a **System property** before any PlantUML class is touched. PlantUML's `SecurityUtils.getenv` reads System properties first, and `SecurityUtils.getSecurityProfile()` caches its value on first call. If the profile is not `SANDBOX` after setup, PlantUML is disabled (fail closed).
- Never display a PlantUML-generated error or welcome image. Errors come only from `PSystemError` (`getLineLocation()`, `getErrorsUml()`).

**Fences and diagram scope**
- Language key `plantuml` only. ` ```plantuml example ` stays code.
- Only `@startuml` diagrams are allowed. `@startuml`/`@enduml` are optional, and the renderer wraps the source when they are missing.
- `!pragma layout smetana` is always forced by the renderer.

**Fail-closed rules**
- `check()` and every resolve path never throw; failures become reason codes.
- Reason codes are lowercase-hyphen: `too-large`, `unsupported-diagram`, `multiple-diagrams`, `include`, `file-access`, `image`, `unsafe-link`, `layout-pragma`, `syntax-error`, `too-complex`, `too-slow`, `output-too-large`, `busy`, `page-budget`, `render-failed`, `plantuml-disabled`.
- Transient reasons, never cached anywhere: `busy`, `page-budget`, `render-failed` (unexpected exceptions). Page caches (`CACHE_HTML`, `EmbedBodyCache`) hold placeholders only, never drawings or outcomes.
- The sanitizer's SVG posture is unchanged: hand-written `<svg>` is still stripped. The only sanitizer change is an inert `data-fence-drawing` attribute on `figure`.

**Project rules**
- Never swallow exceptions; at minimum `LOG.warn` with context.
- Every new `wikantik.*` key is declared in `wikantik-main/src/main/resources/ini/wikantik.properties` under `# [Rendering & wiki syntax]`, with a description, a `Type:` line and the same default as the code (`ConfigSurfaceDriftTest`). Regenerate the docs afterwards with `bin/config-reference.sh --write`.
- Render threads are **daemon** threads. No shutdown hook is needed, and none may block Tomcat shutdown.
- New classes stay under the PMD complexity ratchet (`mvn pmd:check -Pcomplexity-gate`).
- Build the reactor with `install`, not a bare reactor `test`, because dependents need the *shaded* `wikantik-plantuml` jar from the package phase. Maven runs longer than 5 minutes go through `bin/agent-build.sh`.
- TDD: each test is seen to fail before its code exists.
- Commits are 1–3 lines, files are staged by name, and every message ends with the session trailers.

## Review Focus

1. **The same diagram twice on one page.** Both copies draw, and the shared `<defs>` ids are identical but harmless. Pinned in Task 6 by `sameDiagramTwiceDrawsBoth`.
2. **HTML-special and non-ASCII text in sources** (`List<String>`, `a && b`, `Größe`). The resolver must unescape the `<code>` text so that its hash matches the placeholder, with `allowHTML` both on and off. Pinned in Task 6 by `specialCharactersRoundTrip`.
3. **CRLF sources** pasted from Windows. Flexmark LF-normalises them at render time, and the hash must agree at resolve time. Pinned in Task 6 by `crlfSourceResolves`.
4. **Fences nested in blockquotes or list items.** The splice works wherever the envelope lands. Pinned in Task 6 by `nestedFenceResolves`.
5. **An anonymous reader meeting a hand-written browser-kind PlantUML envelope**, so the preview fetch returns 401. The figure shows a "could not be drawn" failure with its source and never breaks the page. Pinned in Task 8 by `serverDraw401ShowsFailure`.

---

### Task 1: `wikantik-plantuml` module, shaded without Ditaa, sandboxed engine

**Files:**
- Create: `wikantik-plantuml/pom.xml`
- Create: `wikantik-plantuml/src/main/java/com/wikantik/plantuml/{PlantUmlSandbox,PlantUmlEngine,PlantUmlDrawing,RenderOutcome}.java`
- Create: `wikantik-plantuml/src/test/java/com/wikantik/plantuml/{PlantUmlSandboxTest,PlantUmlEngineTest}.java`
- Create: `wikantik-war/src/test/java/com/wikantik/architecture/PlantUmlLicenseArchTest.java`
- Modify: root `pom.xml` (add `<module>wikantik-plantuml</module>` before `wikantik-main`, and a `plantuml.version` property plus a dependencyManagement entry following the tika pattern), `wikantik-bom/pom.xml` (add the module), `wikantik-main/pom.xml` (depend on `wikantik-plantuml`), `wikantik-coverage-report/pom.xml` (add it as ingest is added), `NOTICE`

**Interfaces:**
- Produces:
  - `public record PlantUmlDrawing(String lightSvg, String darkSvg)`.
  - `public record RenderOutcome(PlantUmlDrawing drawing, String reason, String message, int line, boolean transientFailure)`, with:
    - `static RenderOutcome drawn(PlantUmlDrawing d)`;
    - `static RenderOutcome refused(String reason, String message, int line)` (`line` 0 = unknown);
    - `static RenderOutcome transientRefusal(String reason)`;
    - `boolean isDrawn()`.
  - `public final class PlantUmlSandbox`, with:
    - `static boolean ensure()`: idempotent. It sets the System property, then returns whether `SecurityUtils.getSecurityProfile() == SecurityProfile.SANDBOX`.
  - `public final class PlantUmlEngine`, with:
    - `static RenderOutcome render(String source)`: raw, unscrubbed SVG for both variants, produced in one call.
    - `static String version()`: `Version.versionString()`.
- `render` behaviour:
  - Wrap the source in `@startuml\n!pragma layout smetana\n…\n@enduml` when it has no `@startuml`; otherwise insert the pragma line directly after `@startuml`.
  - The dark variant uses `FileFormatOption(FileFormat.SVG).withColorMapper(ColorMapper.DARK_MODE)`.
  - A `PSystemError` becomes `refused("syntax-error", firstMessage, lineInAuthorSource)`, with the line mapped back past the injected lines (1-based).
  - `StackOverflowError` becomes `refused("too-complex", …)`.
  - `LinkageError` and `RuntimeException` become `transientRefusal("render-failed")` with `LOG.warn`.
  - When `ensure()` is false, every call returns `transientRefusal("render-failed")` and logs `LOG.error` once.

- [ ] **Step 1: Write the failing tests.**
  - `PlantUmlSandboxTest.sandboxActive`: `assertTrue(PlantUmlSandbox.ensure())`.
  - `includeOfLocalFileFails`: `PlantUmlEngine.render("!include /etc/hostname\nclass A")` → `reason == "syntax-error"`. The policy is not involved, which proves the sandbox alone.
  - `getenvIsEmpty`: the source `title %getenv("HOME")` renders, and neither SVG contains the value of `System.getenv("HOME")`.
  - `PlantUmlEngineTest`:
    - `classDiagramDraws`: a 3-class hierarchy with an interface and a generic; `isDrawn()`; both SVGs start with `<svg` or `<?xml`; the light SVG differs from the dark one.
    - `activityForkJoinDraws`: `start / fork / :a; / fork again / :b; / end fork / stop` is drawn.
    - `syntaxErrorReportsAuthorLine`: line 3 of a 4-line wrapped source is bad → `line == 3`; the same source written with explicit `@startuml` → `line == 4`.
    - `c4StdlibIncludeDraws`: `!include <C4/C4_Container>` plus one `Container(...)` is drawn.
    - `deepRecursionIsTooComplex`: render 300 classes / 600 edges (the spike generator) on a thread with a 512 KB stack → `too-complex`.
- [ ] **Step 2: Write `PlantUmlLicenseArchTest`.**
  - `noDitaaOnRuntimeClasspath`: `getClass().getClassLoader().getResource("org/stathissideris")` is null.
  - `plantUmlPresent`: `net/sourceforge/plantuml/SourceStringReader.class` is present.
  - `noUnshadedPlantUmlJar`: no classpath entry (`System.getProperty("java.class.path")`) has a file name starting with `plantuml-`.
- [ ] **Step 3: Run the tests and see them fail.** Run `mvn test -pl wikantik-plantuml` and expect a compile failure.
- [ ] **Step 4: Write the pom.**
  - Depend on `plantuml-mit` with `<optional>true</optional>`, so it is never transitive. Add `log4j-api` and the test dependencies, including `mockito-core` (test scope) — a module without it kills surefire.
  - Configure `maven-shade-plugin` at `package`: `artifactSet` includes only `net.sourceforge.plantuml:plantuml-mit`; a filter on that artifact excludes `org/stathissideris/**`; `createDependencyReducedPom=false`; `shadedArtifactAttached=false`.
  - Set the coverage floor `<wikantik.coverage.line.minimum>` provisionally to `0.80`; Task 4 sets the measured floor.
- [ ] **Step 5: Implement the four classes.** Then run `mvn test -pl wikantik-plantuml` and expect PASS.
- [ ] **Step 6: Build and check the war.**
  - Run `bin/agent-build.sh start p1 -- mvn clean install -DskipITs -pl wikantik-plantuml,wikantik-main,wikantik-war -am -Dtest='PlantUml*' -Dsurefire.failIfNoSpecifiedTests=false`.
  - Then run `unzip -l wikantik-war/target/*.war | grep -i plantuml`. Expect only `wikantik-plantuml-*.jar`.
  - Then run `unzip -p wikantik-war/target/*.war 'WEB-INF/lib/wikantik-plantuml-*.jar' > /tmp/x.jar && unzip -l /tmp/x.jar | grep -c stathissideris` (use a scratchpad path). Expect `0`.
- [ ] **Step 7: Update `NOTICE`.** Add PlantUML: MIT, © Arnaud Roques, plus one sentence saying that leftover GPL-3 headers in its sources are superseded by the MIT grant of the `plantuml-mit` artifact from the same sole author. Add Smetana: EPL-1.0, © AT&T, with sources in the `plantuml-mit` sources jar on Maven Central. State that Ditaa (LGPL) is removed from the shipped jar.
- [ ] **Step 8: Commit.** Message: `feat(plantuml): isolated shaded PlantUML module, sandboxed engine, Ditaa stripped`.

### Task 2: `PlantUmlPolicy` (pure, in the module)

**Files:**
- Create: `wikantik-plantuml/src/main/java/com/wikantik/plantuml/PlantUmlPolicy.java`
- Test: `wikantik-plantuml/src/test/java/com/wikantik/plantuml/PlantUmlPolicyTest.java`

**Interfaces:**
- Produces:
  - `public record Analysis(String reason, List<String> linkTargets)`, where `reason` is null when the source is allowed; `boolean allowed()`.
  - `public static Analysis analyse(String source)`. It never throws; an unexpected exception gives `render-failed` with `LOG.warn`.
  - `public static final int MAX_SOURCE_BYTES = 65_536` (UTF-8).
  - **Ruling:** the cap is a constant, not a config key, because `check()` runs from the static registry. Spec deviation; the cost if wrong is one later key.

- [ ] **Step 1: Write a parameterised `refusals` test (source → reason).** The rows below are the policy, checked in this order, first match wins.
  - Over `MAX_SOURCE_BYTES` → `too-large`.
  - The first `@start\w+` is not `@startuml`, e.g. `@startgantt` → `unsupported-diagram`.
  - A line `ditaa` (top-level `^\s*ditaa\b`) → `unsupported-diagram`.
  - Two `@startuml` lines → `multiple-diagrams`.
  - `!include foo.puml`, `!includeurl http://x`, `!includesub a!B`, `!include_many x`, `!import x`, `!include <aws/Compute>`, and `!theme cerulean from https://x` → `include`.
  - `!pragma layout elk` and `!pragma graphviz_dot jdot` → `layout-pragma`.
  - `%getenv("X")`, `%load_json("x")`, `%file_exists("x")`, `%dirpath()` and `%filename()` → `file-access`.
  - `class A <<img:x.png>>`, `note: <img src="x">` and `<IMG:x>` → `image`.
  - `class A [[javascript:alert(1)]]`, `[[file:///etc]]`, `[[//evil.example/x]]` and `[[data:x]]` → `unsafe-link`.
- [ ] **Step 2: Write the `file-access` deny-list test.** Before writing it, grep PlantUML 1.2026.8's builtin-function registry (`net.sourceforge.plantuml.tim` / `…tim.builtin`) and deny every builtin that reads files, environment, system properties, the network or the classpath. The test enumerates that list, one row per builtin.
- [ ] **Step 3: Write `allowed` and link-target tests.**
  - `allowed`: `!include <C4/C4_Container>`, a plain class diagram, a fork/join activity, `skinparam`, `!pragma useVerticalIf on`, `!define`, `title X` and `%strlen("a")` are all allowed.
  - `linkTargets`: `class A [[/wiki/PageOne]]`, `B [[/wiki/Page%20Two{tip} label]]`, `C [[#anchor]]` and `D [[https://example.com/x]]` give targets `/wiki/PageOne`, `/wiki/Page%20Two`, `#anchor` and `https://example.com/x`, in order.
- [ ] **Step 4: Run the tests and see them fail.**
- [ ] **Step 5: Implement.** The checks are line-oriented with case-insensitive regexes. A link target is the text after `[[` up to whitespace, `{` or `]`. The allowed targets are `/wiki/…`, `#…`, `http://…` and `https://…`.
- [ ] **Step 6: Run the tests and see them pass.**
- [ ] **Step 7: Commit.** Message: `feat(plantuml): fail-closed source policy`.

### Task 3: `SvgScrubber` (in the module)

**Files:**
- Create: `wikantik-plantuml/src/main/java/com/wikantik/plantuml/SvgScrubber.java`
- Test: `wikantik-plantuml/src/test/java/com/wikantik/plantuml/SvgScrubberTest.java`, plus fixtures `src/test/resources/svg/<type>.svg`

**Interfaces:**
- Produces:
  - `public static String scrub(String svg, String idPrefix)`, which throws `SvgScrubber.Rejected extends RuntimeException` on unparsable input.
  - Parsing uses the JDK DOM with `FEATURE_SECURE_PROCESSING`, `disallow-doctype-decl=true` and no external entities.
- What `scrub` does:
  - **Removes:** comments, processing instructions (including `<?plantuml-src …?>`), elements not on the allow-list (with their whole subtree), and attributes not on the allow-list.
  - **Removes, in addition:** every `on*` attribute; any `style` containing `url(` other than `url(#…)`, `expression`, `@import`, `\` or `javascript`; and any `href`/`xlink:href` that is not `/wiki/…`, `#…` or `http(s)://…`.
  - **Rewrites ids:** every `id` value becomes `idPrefix + value`, and every `url(#x)` and every `href="#x"` that refers to a known id becomes `#idPrefix+x`.

- [ ] **Step 1: Build the fixtures.** Use `PlantUmlEngine` to generate one fixture SVG for each UML type: class, activity (fork/join), sequence, use case, component, state, object, deployment, timing and C4. Commit the files. Derive the element and attribute allow-list from their union, and record it as constants in `SvgScrubber`.
- [ ] **Step 2: Write the tests.**
  - `fixturesSurviveUnchangedExceptIds`: for every fixture, the element count is the same before and after the scrub.
  - `scriptAndForeignObjectRemoved`: `<svg><script>x</script><foreignObject><div/></foreignObject><g/></svg>` keeps no `script` and no `foreignObject`.
  - `onAttributesRemoved`.
  - `javascriptHrefRemoved`.
  - `styleUrlToExternalRemoved`.
  - `idsPrefixedAndReferencesRewritten`: `<defs><linearGradient id="g"/></defs><rect fill="url(#g)"/>` with prefix `wkfabc-` → `id="wkfabc-g"` and `fill="url(#wkfabc-g)"`.
  - `plantumlSrcPiRemoved`.
  - `doctypeRejected` (expects `Rejected`).
- [ ] **Step 3: Run the tests and see them fail.** Then implement, then run them and see them pass.
- [ ] **Step 4: Commit.** Message: `feat(plantuml): allow-list SVG scrub with id prefixing`.

### Task 4: `PlantUmlService` (pool, limits, cache, in-flight join)

**Files:**
- Create: `wikantik-plantuml/src/main/java/com/wikantik/plantuml/{PlantUmlService,PlantUmlSettings}.java`
- Test: `wikantik-plantuml/src/test/java/com/wikantik/plantuml/PlantUmlServiceTest.java`

**Interfaces:**
- Consumes: Tasks 1–3.
- Produces:
  - `public record PlantUmlSettings(boolean enabled, int threads, int queueCapacity, long threadStackBytes, long timeoutMs, int maxSvgBytes, long slowRetryMs, int cacheEntries)`, with `static PlantUmlSettings defaults()` = `(true, 2, 8, 16_777_216, 5_000, 2_097_152, 600_000, 500)`.
  - `public PlantUmlService(PlantUmlSettings s)`.
  - `PlantUmlService(PlantUmlSettings s, Function<String, RenderOutcome> engine)`: a package-private seam for tests; the default is `PlantUmlEngine::render`.
  - `public RenderOutcome render(String source, long waitBudgetMs)`: never throws. The drawing is already scrubbed, with id prefix `"wkf" + sha256hex(source).substring(0, 10) + "l-"` for the light variant and `…"d-"` for the dark.
- `render` behaviour, in this order:
  1. Disabled → `refused("plantuml-disabled", …)`.
  2. `PlantUmlPolicy` refusal → `refused(reason, …)`.
  3. An LRU cache hit (key = `sha256(source) + "|" + PlantUmlEngine.version()`, capacity `cacheEntries`) → the cached outcome.
  4. Join an in-flight `CompletableFuture` for the same key.
  5. Submit to a `ThreadPoolExecutor(threads, threads, ArrayBlockingQueue(queueCapacity))` whose daemon threads are made with `new Thread(null, r, "wikantik-plantuml-N", threadStackBytes)`. A rejected submission → `transientRefusal("busy")`.
  6. Wait `min(timeoutMs, waitBudgetMs)`:
     - If the budget is the binding limit, return `transientRefusal("page-budget")` and let the render continue; its outcome is cached when it completes.
     - If the timeout is the binding limit, `cancel(true)` the render and record `too-slow` in the cache with a TTL of `slowRetryMs`. After it expires, one retry is allowed; a second timeout caches `too-slow` permanently.
- Outcome on completion:
  - Either SVG longer than `maxSvgBytes` → `refused("output-too-large")`, cached.
  - A scrub `Rejected` → `transientRefusal("render-failed")` with `LOG.warn`.
  - `syntax-error` and `too-complex` → cached.
  - Transient outcomes are never cached.

- [ ] **Step 1: Write the tests, using the engine seam with latches.**
  - `cachesDrawnOutcome`: the engine is called once for two renders.
  - `syntaxErrorCached`.
  - `busyWhenPoolAndQueueFull`: `threads=1, queue=0`, a blocked first render, then a second distinct source → `busy`, not cached.
  - `concurrentSameSourceJoinsInFlight`: two threads, one engine call.
  - `budgetShorterThanTimeoutGivesPageBudgetAndLaterCaches`: budget 50 ms, engine 200 ms → `page-budget`; after the latch, the next call is drawn with no new engine call.
  - `timeoutGivesTooSlowThenRetriesOnceAfterTtl`: `slowRetryMs` 100 ms. A third timeout stays `too-slow` without calling the engine.
  - `disabledRefuses`.
  - `policyRefusalNeverReachesEngine`.
  - `outputTooLarge`: `maxSvgBytes=10`.
  - `threadsAreDaemon`.
  - `realRenderScrubbed`: the real engine; the drawn SVG has no `<?plantuml-src` and its ids start with `wkf`.
- [ ] **Step 2: Run the tests and see them fail.** Then implement, then run them and see them pass.
- [ ] **Step 3: Set the coverage floor.** Run `mvn clean install -Pcoverage -DskipITs -pl wikantik-plantuml` and set `wikantik.coverage.line.minimum` to the measured line coverage, rounded down to a whole percent and capped at 0.90.
- [ ] **Step 4: Commit.** Message: `feat(plantuml): bounded render service with failure caching and in-flight join`.

### Task 5: Hook contract change: placeholders, `PlantUmlFenceRenderer`, sanitizer attribute

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/markdown/extensions/fences/{FencePlaceholders,PlantUmlFenceRenderer}.java`
- Modify:
  - `RenderedFenceRenderer.java` (remove `renderDrawing`; rewrite the server-kind Javadoc section to describe the placeholder → resolve flow, `ServerFenceDrawer`, the scrub and `ServerFenceResolver`)
  - `RenderedFenceNodeRenderer.java:58-82`
  - `RenderedFenceRegistry.java:29` (defaults = Mermaid + PlantUML)
  - `WikantikHtmlSanitizer.java:59-61`
  - `wikantik-main/src/test/.../fences/FakeServerRenderer.java`
- Test: `FencePlaceholdersTest`, `PlantUmlFenceRendererTest`, plus updates to `RenderedFenceRenderingTest`, `RenderedFenceRegistryTest` and `WikantikHtmlSanitizerTest`

**Interfaces:**
- Produces:
  - `FencePlaceholders.sign(String source) → String`: `sha256hex(source) + "." + hmacSha256hex(secret, sha256hex)`. The secret is 32 bytes of `SecureRandom`, per JVM.
  - `FencePlaceholders.verify(String token, String source) → boolean`: constant-time comparison; it also checks the hash against `source`.
- Ruling: the secret is per JVM, as spec'd. Placeholders cached by the opt-in memcached adapter on another node fail to verify and show their source. Document this in the Javadoc.
- The server-kind envelope at render time is the existing figure plus `data-fence-drawing="<token>"`, with no drawing `div`. A refused server fence keeps `data-fence-error` and gets no token.
- `PlantUmlFenceRenderer`:
  - `language()` is `"plantuml"` and `kind()` is `SERVER`.
  - `check()` maps `PlantUmlPolicy.analyse`.
  - `pageLinks()` maps the `/wiki/` targets through `MermaidUrlRules.pageName`, and returns empty when the source is refused.
- Sanitizer: allow `data-fence-drawing` on `figure` only when it matches `[0-9a-f]{64}\.[0-9a-f]{64}`.

- [ ] **Step 1: Write the tests.**
  - `FencePlaceholdersTest`: `signIsStable`; `verifyRejectsTamperedHash`; `verifyRejectsTamperedMac`; `verifyRejectsOtherSource`.
  - `PlantUmlFenceRendererTest`: `refusesInclude`; `pageLinksFromWikiTargets`, where `[[/wiki/PageOne]]` and `[[/wiki/Page%20Two label]]` give `{PageOne, Page Two}`; `noLinksWhenRefused`; `checkNeverThrowsOnNull`.
  - `RenderedFenceRenderingTest`:
    - `serverFenceEmitsVerifiablePlaceholderAndSource`, using the fake server renderer: the figure has `data-fence-drawing`, `FencePlaceholders.verify` is true and the `<pre class="wk-fence-source">` is present.
    - `refusedServerFenceHasNoPlaceholder`.
    - `plantumlExampleStaysCode`.
  - `WikantikHtmlSanitizerTest`: `fenceDrawingTokenSurvives`; `malformedFenceDrawingStripped`; `handWrittenSvgStillStripped`.
  - `RenderedFenceRegistryTest`: `defaultsHaveMermaidAndPlantuml`.
- [ ] **Step 2: Run the tests and see them fail.** Then implement, then run `mvn test -pl wikantik-main -Dtest='*Fence*,WikantikHtmlSanitizerTest'` and expect PASS.
- [ ] **Step 3: Check that `DefaultReferenceManager` picks up PlantUML links.**
  - Add `DefaultReferenceManagerTest#plantumlDiagramLinksCountAsReferences`: a page whose ` ```plantuml ` fence contains `[[/wiki/Target]]` lists `Target` among its references.
  - No reference-cache version bump is needed: no stored page has a `plantuml` fence.
- [ ] **Step 4: Commit.** Message: `feat(fences): server fences emit signed placeholders; PlantUML parse-time renderer`.

### Task 6: `ServerFenceResolver` and wiring at the outermost render

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/markdown/extensions/fences/{ServerFenceDrawer,ServerDrawing,ServerFenceResolver,PlantUmlFenceDrawer}.java`
- Modify:
  - `wikantik-main/src/main/java/com/wikantik/render/RenderingManager.java` (add the two defaults below)
  - `DefaultRenderingManager.java` (`initialize`, `getHTML(Context, WikiDocument)`, `textToHTML(Context, String)`)
  - `wikantik-rest/src/main/java/com/wikantik/rest/PageResource.java:888-892` (`handleEmbed`)
  - `wikantik-main/src/main/resources/ini/wikantik.properties`
- Test: `ServerFenceResolverTest`, `PlantUmlFenceDrawerTest`, `DefaultRenderingManagerServerFenceTest`, plus a `PageResource` embed test

**Interfaces:**
- Consumes: Task 4 `PlantUmlService`/`PlantUmlSettings`; Task 5 `FencePlaceholders`.
- Produces:
  - `public record ServerDrawing(String lightSvg, String darkSvg, String reason, String message, int line, boolean transientFailure)`, with `drawn(..)`, `refused(..)` and `isDrawn()`.
  - `public interface ServerFenceDrawer { String language(); ServerDrawing draw(String source, long budgetMs); long defaultBudgetMs(); }` (`PlantUmlFenceDrawer` returns `timeoutMs`).
  - `PlantUmlFenceDrawer implements ServerFenceDrawer`, with `static PlantUmlFenceDrawer fromProperties(Properties p)`. It reads the keys below and adapts `PlantUmlService`.
  - `public final class ServerFenceResolver`, with:
    - `ServerFenceResolver(Map<String, ServerFenceDrawer> drawers, int pageMaxDiagrams, long pageBudgetMs)`;
    - `static final ServerFenceResolver NONE`;
    - `String resolve(String html)`, which never throws.
  - `RenderingManager`:
    - `default String resolveServerFences(String html) { return html; }`;
    - `default Optional<ServerFenceDrawer> serverFenceDrawer(String language) { return Optional.empty(); }`.
- `resolve` algorithm:
  - **Fast path:** return the input unchanged when it does not contain `data-fence-drawing="`.
  - **Matching:** match every opening `<figure …data-fence-drawing="(hash).(mac)"…>`. Read `data-fence` from the same tag. Take the text of the next `<code class="language-<lang>">…</code>` and HTML-unescape it (`&lt; &gt; &amp; &quot; &#39;` and numeric entities).
  - **Verification:** `FencePlaceholders.verify` must succeed, or the token is just removed and the source stays shown.
  - **Budget:** process figures in document order, with at most `pageMaxDiagrams` draws and a shared `pageBudgetMs` deadline; each figure gets the remaining budget as its `budgetMs`. A figure past either limit becomes `page-budget`.
  - **On success**, the token is replaced by `data-fence-state="drawn"`, and inserted straight after the opening tag is:
    ```
    <div class="wk-fence-drawing" role="img" aria-label="LABEL"><div class="wk-fence-variant" data-variant="light">LIGHT</div><div class="wk-fence-variant" data-variant="dark" hidden>DARK</div></div>
    ```
    LABEL is the source's `title …` line text, escaped, or `PlantUML diagram`.
  - **On refusal**, the token is replaced by `data-fence-error="reason"`, plus `data-fence-error-line="N"` when `N > 0` and `data-fence-error-message="escaped message"` when a message exists.
- Wiring in `DefaultRenderingManager`:
  - `initialize` builds `serverFences = new ServerFenceResolver(Map.of("plantuml", PlantUmlFenceDrawer.fromProperties(properties)), pageMaxDiagrams, pageBudgetMs)`. If the build fails (or the sandbox is false), it uses a drawer that refuses every fence with `render-failed`, and logs `LOG.error`.
  - A `private static final ThreadLocal<int[]> RENDER_DEPTH` counts nesting:
    - `getHTML(Context, WikiDocument)` increments it on entry and decrements it in a `finally`.
    - `textToHTML(Context, String)` records `outermost = RENDER_DEPTH.get()[0] == 0` on entry, and returns `outermost ? serverFences.resolve(result) : result`. This applies to the cache-hit return as well.
    - The HTML cache always stores the unresolved `result`.
  - `resolveServerFences` and `serverFenceDrawer` delegate to it.
  - `PageResource.handleEmbed` wraps the `postTranslate(...)` result in `renderingManager.resolveServerFences(...)`.
- Config keys go in `# [Rendering & wiki syntax]`:

  | Key | Default | Type |
  |---|---|---|
  | `wikantik.fences.plantuml.enabled` | `true` | boolean |
  | `wikantik.fences.plantuml.threads` | `2` | int |
  | `wikantik.fences.plantuml.queueCapacity` | `8` | int |
  | `wikantik.fences.plantuml.threadStackBytes` | `16777216` | long |
  | `wikantik.fences.plantuml.timeoutMs` | `5000` | long |
  | `wikantik.fences.plantuml.maxSvgBytes` | `2097152` | int |
  | `wikantik.fences.plantuml.slowRetryMinutes` | `10` | int |
  | `wikantik.fences.plantuml.cacheEntries` | `500` | int |
  | `wikantik.fences.plantuml.pageMaxDiagrams` | `20` | int |
  | `wikantik.fences.plantuml.pageBudgetMs` | `10000` | long |

  Each key is read at startup.

- [ ] **Step 1: Write the resolver tests,** using a fake drawer and real `FencePlaceholders`.
  - `drawnSplicesBothVariantsAndState`.
  - `refusalSetsErrorLineAndMessage`.
  - `forgedTokenResolvesToNothing`: a valid-looking token with a wrong MAC → no drawing, no state, token removed, source present.
  - `pageMaxDiagramsEnforced`: 21 figures, `pageMaxDiagrams=20` → the last one is `page-budget`.
  - `pageBudgetEnforced`: a fake drawer that sleeps 30 ms, a 50 ms budget → later figures are `page-budget`.
  - `htmlWithoutTokensUnchanged`: the output is the same instance.
  - `sameDiagramTwiceDrawsBoth`.
  - `specialCharactersRoundTrip`: a source with `List<String>`, `a && b` and `Größe`, rendered through the real Flexmark pipeline with `allowHTML` on and off → drawn.
  - `crlfSourceResolves`.
  - `nestedFenceResolves`: inside `> ` and inside `- ` list content.
- [ ] **Step 2: Write the `DefaultRenderingManager` tests** (seams per `reference_render_cache_test_seams`: swap in a resolver over a counting fake drawer).
  - `pageViewResolvesAndCacheHoldsPlaceholder`: a `PAGE_VIEW` render returns the drawing, and `HtmlViewCache.lookup` holds `data-fence-drawing` and no `<svg`.
  - `secondViewDrawsAgainFromPlaceholder`.
  - `transientFailureNeverCached`: the first render is `busy`, the second draws.
  - `nestedInsertPageDoesNotResolveTwice`: the drawer is called exactly once for a page that InsertPages a page containing a diagram, and the drawing survives with `allowHTML` on.
  - `embedWithAllowHtmlDraws`: a `![[Other]]` embed whose body has a diagram, with `allowHTML` on → the drawing is present in the host page's HTML.
  - `embedEndpointResolves`: a `PageResource` test of `/api/pages/X/embed` → the JSON `html` has `data-fence-state="drawn"`.
  - `killSwitchKeepsLinksAndRefuses`: `enabled=false` → `plantuml-disabled`, and the page's references still include the diagram link.
- [ ] **Step 3: Run the tests and see them fail.** Then implement and declare the keys. Run `mvn test -pl wikantik-main -Dtest='*ServerFence*,PlantUmlFenceDrawerTest,*Fence*'` and expect PASS. Then run `mvn test -pl wikantik-war -Dtest=ConfigSurfaceDriftTest` and expect PASS. Then run `bin/config-reference.sh --write`.
- [ ] **Step 4: Commit.** Message: `feat(fences): resolve server drawings at the outermost render; caches hold placeholders`. Include the regenerated docs.

### Task 7: Preview endpoint `POST /api/fences/render`

**Files:**
- Create: `wikantik-rest/src/main/java/com/wikantik/rest/FenceRenderResource.java`
- Modify: `wikantik-war/src/main/webapp/WEB-INF/web.xml` (servlet `FenceRenderResource`, url-pattern `/api/fences/*`, next to `ConvertResource`), and `CLAUDE.md` (the `/api/*` row becomes 37 distinct servlets and 39 url-patterns, and mentions `POST /api/fences/render`)
- Test: `wikantik-rest/src/test/java/com/wikantik/rest/FenceRenderResourceTest.java`

**Interfaces:**
- Consumes: `RenderingManager.serverFenceDrawer(language)` (Task 6).
- Produces:
  - `POST /api/fences/render` with body `{language, source, page?}`. `page` is accepted and ignored; **ruling:** it is reserved, with no behaviour.
  - On success, 200 `{lightSvg, darkSvg}`.
  - On a refusal, 200 `{error: reason, message?, line?}`.
  - A missing `language`/`source` or invalid JSON → 400.
  - A language with no server drawer → 400 `{error: "unsupported-language"}`.
  - An unauthenticated caller → 401, with the same check as `ConvertResource.java:68-73`.
  - A path other than `render` → 404.
  - The response sets `Cache-Control: no-store`.
  - The budget is `drawer.defaultBudgetMs()`.
  - CSRF: `CsrfProtectionFilter` already covers `/api/` POSTs (`CsrfProtectionFilter.java:216`). Add no code; add the test `csrfTokenRequired`, which follows the existing CSRF filter test pattern for a `/api/` POST without a token.

- [ ] **Step 1: Write the tests.** Follow the `ConvertResource` test pattern.
  - `unauthenticated401`.
  - `drawsForAuthenticatedUser`: the real PlantUML class diagram → `lightSvg` starts with `<svg`.
  - `syntaxErrorReturnsLine`.
  - `policyRefusalReturnsReason`: `!include x` → `include`.
  - `browserLanguage400`: `mermaid`.
  - `badJson400`.
  - `unknownAction404`.
- [ ] **Step 2: Run the tests and see them fail.** Then implement and register the servlet, then run them and see them pass.
- [ ] **Step 3: Commit.** Message: `feat(rest): POST /api/fences/render for editor previews of server fences`.

### Task 8: Frontend — server figures, variants, preview fetch

**Files:**
- Create: `wikantik-frontend/src/utils/fences/serverRenderer.js` (+ `serverRenderer.test.js`)
- Modify:
  - `registry.js` (header how-to gains a "server kind" section; entries gain `kind`)
  - `drawFence.js`
  - `renderFences.js`
  - `envelope.js`
  - `components/WikiFence.jsx`
  - `utils/livePreview/widgets.js:256-268`
  - `styles/fences.css`
  - `api/client.js` (`renderFence(language, source)`)
- Test: `drawFence.test.js`, `renderFences.test.js`, `WikiFence.test.jsx`, `serverRenderer.test.js`

**Interfaces:**
- Consumes: the Task 6 server markup, and the Task 7 endpoint.
- Produces:
  - `FENCE_RENDERERS.plantuml = { kind: 'server', check: () => ({ allowed: true }), load: () => import('./serverRenderer.js') }`. Mermaid gains `kind: 'browser'`.
  - `serverRenderer.draw(host, source, { language, isStale })`:
    - Waits 500 ms, then returns early if `isStale()`.
    - Calls `api.renderFence`.
    - On `{lightSvg, darkSvg}`, fills `host` with the two `div.wk-fence-variant` children, in exactly Task 6's markup.
    - On `{error, message, line}`, throws an `Error` whose message is `PlantUML line N: message` (or `PlantUML: <reason>`).
    - On HTTP failure (including 401), it throws.
  - `drawFence.js` exports `decorateServerFence(figure)`:
    - When `data-fence-state="drawn"` and there is no toolbar, it adds the shared toolbar and the Expand check.
    - When `data-fence-error` is set and there is no state, it shows the problem box from the reason, with `data-fence-error-line` and `data-fence-error-message` in the detail, and sets state `refused`.
    - It is idempotent.
- Behaviour changes:
  - `errorSummary`'s `Mermaid:` prefix becomes the renderer's language label.
  - `redrawFence` returns `'skipped'` for `entry.kind === 'server'`, because CSS switches the theme.
  - `openDialog`'s clone rewrites every `id` and every `#id` reference (`href`, `xlink:href`, `url(#…)`) with a `-x` suffix. This also fixes Mermaid's deferred duplicate-id issue.
  - `renderFences` also runs `decorateServerFence` over `figure.wk-fence[data-fence-kind="server"]`, with no lazy import when there are none.
  - `fillFenceEnvelope` sets `data-fence-kind` from the registry entry's `kind`.
  - `WikiFence`: for a server-kind entry, the previous drawn `.wk-fence-drawing` stays visible (with `data-fence-pending`) until the new draw settles, and is then replaced.
  - **Ruling:** the CodeMirror live-preview widget debounces but does not keep the last drawing. Its DOM is recreated on every source change; the preview pane keeps it.
- CSS:
  - `[data-theme="dark"] .wk-fence-variant[data-variant="light"] { display: none }`
  - `[data-theme="dark"] .wk-fence-variant[data-variant="dark"] { display: block }`
  - The `hidden` attribute keeps the dark variant hidden otherwise.

- [ ] **Step 1: Write the tests (vitest).**
  - `serverRenderer.test.js`: `debouncesThenFetches`, using fake timers; `staleSkipsFetch`; `fillsBothVariants`; `errorCarriesLine`; `serverDraw401ShowsFailure`, where `drawFence` on a hand-written browser-kind PlantUML envelope ends with state `failed` and the source visible.
  - `drawFence.test.js`: `decorateServerFenceAddsToolbarOnce`; `decorateServerFenceShowsRefusalWithLine`; `redrawSkipsServerKind`; `expandCloneHasNoDuplicateIds`, for both Mermaid and server SVGs.
  - `renderFences.test.js`: `serverFiguresDecoratedWithoutLoadingRenderers`.
  - `WikiFence.test.jsx`: `serverKindKeepsLastDrawingWhilePending`.
- [ ] **Step 2: Run the tests and see them fail.** Run `cd wikantik-frontend && npx vitest run src/utils/fences src/components/WikiFence.test.jsx` and expect FAIL.
- [ ] **Step 3: Implement.** Then run the same command and expect PASS. Then run `npm run build`; expect it to pass with `check-dist.js` pristine.
- [ ] **Step 4: Commit.** Message: `feat(frontend): server-drawn fences get toolbar, theme variants and server previews`.

### Task 9: Corpus checker `bin/check-plantuml.sh`

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/markdown/extensions/fences/PlantUmlCheckCli.java`, and `bin/check-plantuml.sh`
- Test: `wikantik-main/src/test/java/com/wikantik/markdown/extensions/fences/PlantUmlCheckCliTest.java`

**Interfaces:**
- Consumes: `RenderedFenceScanner.scan`, `PlantUmlFenceRenderer.check`, and `PlantUmlService` with defaults.
- Produces:
  - `PlantUmlCheckCli.run(String[] args, InputStream in, PrintStream out, PrintStream err) → int`, with `main` calling `System.exit(run(...))`.
  - The usage contract is the same as `check-mermaid.mjs`: `[--json] <files|dirs|->`; a directory is walked for `*.md`.
  - Exit codes: 0 when every PlantUML fence draws; 1 when any is refused or fails, printing `file:fenceIndex: reason [line N]: message`; 2 on a checker error such as a missing path.
  - `--json` prints `[{file, index, reason, line, message}]`.
  - `bin/check-plantuml.sh` caches `mvn -q dependency:build-classpath -pl wikantik-main -Dmdep.outputFile=wikantik-main/target/plantuml-check.classpath` when that file is absent, then runs `java -cp "wikantik-main/target/classes:$(cat …)" com.wikantik.markdown.extensions.fences.PlantUmlCheckCli "$@"`.

- [ ] **Step 1: Write the tests.** `allGoodExit0`; `refusedExit1WithReason`; `syntaxErrorReportsLine`; `jsonOutput`; `missingPathExit2`; `exampleFencesIgnored`.
- [ ] **Step 2: Run the tests and see them fail.** Then implement, then run them and see them pass.
- [ ] **Step 3: Smoke-test the script.** Run `printf '```plantuml\nclass A\n```\n' | bin/check-plantuml.sh -` and expect exit 0.
- [ ] **Step 4: Commit.** Message: `feat(fences): PlantUML corpus checker for the content agent`.

### Task 10: Selenide IT

**Files:**
- Modify: `wikantik-it-tests/wikantik-selenide-tests/src/main/java/com/wikantik/its/RenderedFencesIT.java`, and its fixture page(s) under that module's IT test resources (follow how the Mermaid fixture page is seeded; per `reference_it_structural_index_seed_lag`, open startup fixtures)

**Interfaces:**
- Consumes: everything above, through the built WAR.

- [ ] **Step 1: Add the tests.**
  - `plantumlClassAndActivityDrawInReader`: a page with one class and one fork/join activity fence → two `figure[data-fence="plantuml"][data-fence-state="drawn"] .wk-fence-variant[data-variant="light"] svg`, and a visible toolbar "Show source" button.
  - `plantumlDarkVariantShownInDarkTheme`: toggle the theme → the dark variant is displayed and the light one is not.
  - `plantumlPreviewDrawsInEditor`: an authenticated user opens the editor for the page → the preview pane's figure reaches `data-fence-state="drawn"`.
  - `plantumlRefusalShowsReason`: a page with `!include x` → `figure[data-fence-error="include"]` and the source visible.
- [ ] **Step 2: Run** `bin/run-tests.sh --parallel 4` via `bin/agent-build.sh` and expect PASS. Per memory, check the per-module results, not just the last summary line.
- [ ] **Step 3: Commit.** Message: `test(it): PlantUML fences draw in reader, theme and editor preview`.

### Task 11: Docs and repo-side rollout material

**Files:**
- Modify:
  - `CLAUDE.md`: the module list gains `wikantik-plantuml` (it isolates PlantUML; shaded; Ditaa stripped; `PlantUmlLicenseArchTest`), and the "Rendered fences" bullet names PlantUML (server kind, placeholders resolved at the outermost `textToHTML`, `bin/check-plantuml.sh`).
  - `docs/wikantik-pages/EditPageHelp.md`: a PlantUML subsection under Diagrams, with a fork/join activity example, a class-hierarchy example, the `[[/wiki/Page]]` link syntax and "use PlantUML for class/activity, Mermaid otherwise". Keep the prod frontmatter, as the earlier mirror commit did.
  - `docs/wikantik-pages/RenderedFencesDesign.md` mirror: the server path.
  - `docs/developer/ProjectReference.md`: the status line.

- [ ] **Step 1: Make the edits.** Then run `bin/run-tests.sh --parallel 4` (via `bin/agent-build.sh`) as the final gate, including the PMD ratchet and npm audit, and expect all phases green.
- [ ] **Step 2: Commit.** Message: `docs: PlantUML fences in CLAUDE.md, EditPageHelp and design mirror`.

**After release (not part of this plan's execution; the owner picks the version):**
- Verify that no prod page already has a ` ```plantuml ` fence, using `search_knowledge` / `list_pages_by_filter`.
- Push `EditPageHelp` with `bin/remote.sh pages-push`.
- Update the live `RenderedFencesDesign` and `PlantUmlFencesDesign` pages through MCP.
- Update the guidance in `~/source/wiki-content-agent`, with the linter calling `bin/check-plantuml.sh`.
