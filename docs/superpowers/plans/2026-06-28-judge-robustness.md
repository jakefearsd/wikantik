# Judge Robustness (Theme B) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the two remaining graceful-degradation findings in the KG judge/materialization path — #3 (judge JSON robustness: missing-content NPE + redundant parse-WARN spam) and #4 (guard-rejections logged at WARN as if they were errors) — without changing any verdict semantics.

**Architecture:** Two independent, small Java changes. #3 hardens `DefaultKgProposalJudgeService.parseResponse` (an explicit null-guard + demote one WARN) and adds regression tests locking in the already-working fence/truncation behavior. #4 reclassifies two WARN logs (SHACL non-conformant skip; closed-vocabulary skip) to INFO with "rejected-by-guard" wording — these are guards working as designed, already counted. TDD throughout; no schema change; GPU-independent.

**Tech Stack:** Java 21, JUnit 5, Mockito, SLF4J. Build/test: `mvn test -pl wikantik-main -Dtest=<Class>` (judge) and `mvn test -pl wikantik-ontology -Dtest=<Class>` (EdgeProjector).

## Global Constraints

- **No verdict-semantics change.** Parse failures must keep degrading to a transient abstain (`verdict==ABSTAIN`, rationale `startsWith("judge_unavailable:")`) so `JudgeRunner`'s tick-summary (fix #2) still counts them and they retry next tick. Do not change the abstain classification — only the missing-content path's clarity and one log level.
- **Never swallow exceptions silently** (CLAUDE.md): every catch logs at least `LOG.warn` with context — except where this plan deliberately demotes an already-tick-summarized WARN to DEBUG/INFO; those still log, just at a lower level.
- **Guard-rejections are expected outcomes, not failures.** The SHACL non-conformant skip (`KgMaterializationService`) and the closed-vocabulary skip (`EdgeProjector`) are the gate working correctly. Reclassify to INFO; keep the existing counter (`skippedNonConformant`) as the observability signal; the message must read as "rejected/skipped by guard," not "failed."
- **TDD:** failing test first, per CLAUDE.md.
- **Out of scope (deferred, measure-first):** the extractor proposing many out-of-vocab predicates (synonym-map / prompt tuning). The roadmap requires measuring real rejection frequency before changing the extractor — do NOT add a synonym map blind. Recorded as a follow-up in Task 2.

---

### Task 1: Judge JSON robustness (#3)

Harden `DefaultKgProposalJudgeService.parseResponse`: a missing `content` key currently NPEs (caught into a generic `parse error`); make it an explicit, clearly-labelled transient abstain. Demote the per-failure parse WARN (now redundant — the JudgeRunner tick-summary aggregates these). Lock in the already-working fence + truncation behavior with regression tests.

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/knowledge/judge/DefaultKgProposalJudgeService.java` (`parseResponse`, lines ~374–401)
- Test: `wikantik-main/src/test/java/com/wikantik/knowledge/judge/DefaultKgProposalJudgeServiceJsonExtractTest.java`

**Interfaces:**
- Consumes: existing `extractJsonObject(String)`, `abstain(String)`, `JudgeVerdict`, the test helpers `cfg()`, `mockResponse(int,String)`, `httpClientReturning(resp)`, `escapeJsonString(String)`, `sampleProposal()` already in `DefaultKgProposalJudgeServiceJsonExtractTest`.
- Produces: `parseResponse` returns `abstain("judge_unavailable: response missing content")` when the `message` object has no string `content`; all parse failures still produce `judge_unavailable:`-prefixed abstains.

- [ ] **Step 1: Write the failing tests**

Add to `DefaultKgProposalJudgeServiceJsonExtractTest`:

```java
@Test
void judge_fenced_json_response_parses_successfully() throws Exception {
    // LLM wraps the verdict object in ```json fences — extractJsonObject must still recover it.
    final String inner = "```json\n"
        + "{\"verdict\":\"approved\",\"confidence\":0.9,\"rationale\":\"Well grounded.\"}\n```";
    final String body = "{\"message\":{\"role\":\"assistant\",\"content\":"
        + escapeJsonString( inner ) + "}}";
    final var svc = new DefaultKgProposalJudgeService( httpClientReturning( mockResponse( 200, body ) ), cfg() );
    final JudgeVerdict v = svc.judge( sampleProposal() );
    assertEquals( "approved", v.verdict() );
    assertEquals( 0.9, v.confidence() );
    assertEquals( "Well grounded.", v.rationale() );
}

@Test
void judge_truncated_response_degrades_to_transient_abstain() throws Exception {
    // Truncated mid-object (unbalanced braces) — must NOT crash; must be a transient abstain.
    final String inner = "{\"verdict\":\"approved\",\"confidence\":0.9,\"rationale\":\"Well groun";
    final String body = "{\"message\":{\"role\":\"assistant\",\"content\":"
        + escapeJsonString( inner ) + "}}";
    final var svc = new DefaultKgProposalJudgeService( httpClientReturning( mockResponse( 200, body ) ), cfg() );
    final JudgeVerdict v = svc.judge( sampleProposal() );
    assertEquals( JudgeVerdict.ABSTAIN, v.verdict() );
    assertTrue( v.rationale().startsWith( "judge_unavailable:" ),
        "truncation must yield a transient (judge_unavailable:) abstain, got: " + v.rationale() );
}

@Test
void judge_message_without_content_key_is_transient_abstain_not_npe() throws Exception {
    // message object present but no "content" — previously NPE'd into a generic parse error.
    final String body = "{\"message\":{\"role\":\"assistant\"}}";
    final var svc = new DefaultKgProposalJudgeService( httpClientReturning( mockResponse( 200, body ) ), cfg() );
    final JudgeVerdict v = svc.judge( sampleProposal() );
    assertEquals( JudgeVerdict.ABSTAIN, v.verdict() );
    assertEquals( "judge_unavailable: response missing content", v.rationale() );
}
```

- [ ] **Step 2: Run the tests to confirm the relevant ones fail**

Run: `mvn test -pl wikantik-main -Dtest=DefaultKgProposalJudgeServiceJsonExtractTest -q`
Expected: `judge_message_without_content_key_is_transient_abstain_not_npe` FAILS (current code NPEs → caught → rationale is `judge_unavailable: parse error`, not `... missing content`). The fenced + truncated tests likely already PASS (they characterize existing behavior — that's fine; they are regression guards).

- [ ] **Step 3: Add the explicit null-guard + demote the parse WARN**

In `parseResponse`, replace the `message`/`content` extraction and the catch's WARN. Current:

```java
            if ( outerObj.has( "message" ) && outerObj.get( "message" ).isJsonObject() ) {
                inner = outerObj.getAsJsonObject( "message" ).get( "content" ).getAsString();
            } else if ( outerObj.has( "response" ) ) {
```

becomes (guard a missing/non-string `content`):

```java
            if ( outerObj.has( "message" ) && outerObj.get( "message" ).isJsonObject() ) {
                final JsonObject msg = outerObj.getAsJsonObject( "message" );
                if ( !msg.has( "content" ) || !msg.get( "content" ).isJsonPrimitive() ) {
                    return abstain( "judge_unavailable: response missing content" );
                }
                inner = msg.get( "content" ).getAsString();
            } else if ( outerObj.has( "response" ) ) {
```

And demote the redundant per-failure WARN in the catch (the JudgeRunner tick-summary already aggregates these transient abstains):

```java
        } catch ( final RuntimeException e ) {
            LOG.debug( "judge response parse failure: {}", e.getMessage() );
            return abstain( "judge_unavailable: parse error" );
        }
```

- [ ] **Step 4: Run the tests to confirm they pass**

Run: `mvn test -pl wikantik-main -Dtest=DefaultKgProposalJudgeServiceJsonExtractTest -q`
Expected: all tests PASS (including the new three).

- [ ] **Step 5: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/knowledge/judge/DefaultKgProposalJudgeService.java \
        wikantik-main/src/test/java/com/wikantik/knowledge/judge/DefaultKgProposalJudgeServiceJsonExtractTest.java
git commit -m "fix(judge): explicit missing-content guard + demote redundant parse WARN (#3)"
```

---

### Task 2: Reclassify guard-rejection logs (#4)

The SHACL non-conformant skip and the closed-vocabulary skip are the ontology gate working as designed — they belong at INFO with "rejected-by-guard" wording, not WARN. The SHACL skip is already counted (`skippedNonConformant`); keep that as the signal.

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/knowledge/judge/KgMaterializationService.java:144` (SHACL non-conformant skip)
- Modify: `wikantik-ontology/src/main/java/com/wikantik/ontology/projection/EdgeProjector.java:54` (closed-vocabulary skip) + its line-50 javadoc ("logged at WARN")
- Test: `wikantik-main/src/test/java/com/wikantik/knowledge/judge/KgMaterializationServiceMaterializeTest.java` (the materialize-path test; SHACL skip behavior lives here). `EdgeProjectorTest.unknownRelationshipIsSkipped` already covers the closed-vocab skip.

**Interfaces:**
- Consumes: existing `skippedNonConformant` AtomicLong + `skippedNonConformantCount()` getter in `KgMaterializationService`; the existing `ontologyValidator.validateEdge(...)` path.
- Produces: the non-conformant edge is still skipped (not written) and `skippedNonConformantCount()` still increments — behavior unchanged; only the log level/wording change.

- [ ] **Step 1: Write/confirm the behavioral test (failing or characterizing)**

Open `KgMaterializationServiceMaterializeTest`. If it already asserts the SHACL non-conformant skip (edge not written, `skippedNonConformantCount()` increments), no new test is needed — that behavioral test guards the log-level edit. If it does not, add one using the existing mocks in that class (mock `ontologyValidator.validateEdge` to return a non-empty violation list; assert `edges.upsertEdgeWithProvenance` is never called and `skippedNonConformantCount()` increments). The log level itself is not asserted — it is a cosmetic change verified by reading. `EdgeProjectorTest.unknownRelationshipIsSkipped` already guards the EdgeProjector behavior.

Run: `mvn test -pl wikantik-main -Dtest=KgMaterializationServiceMaterializeTest -q` — expected PASS (behavior already correct).

- [ ] **Step 2: Reclassify the two logs**

`KgMaterializationService.java:144` — change `LOG.warn` to `LOG.info` and reword so it reads as a guard outcome, not a failure:

```java
                LOG.info( "materialize: rejected ontology-non-conformant edge for proposal {} "
                    + "({} --{}--> {}): {} [skipped by SHACL gate, count={}]",
                    proposal.id(), src.nodeType(), rel, tgt.nodeType(),
                    violations.get( 0 ).message(), skippedNonConformant.get() );
```

`EdgeProjector.java:54` — change `LOG.warn` to `LOG.info` and reword:

```java
            LOG.info( "projection: rejected edge {} -> {}: relationship_type '{}' not in closed vocabulary [guard]",
```

Update the `EdgeProjector` line-50 javadoc to say "logged at INFO (guard outcome), never silently dropped."

- [ ] **Step 3: Run the tests**

Run: `mvn test -pl wikantik-main -Dtest=KgMaterializationServiceMaterializeTest -q` and
`mvn test -pl wikantik-ontology -Dtest=EdgeProjectorTest -q`.
Expected: PASS (behavior unchanged; `EdgeProjectorTest.unknownRelationshipIsSkipped` guards the closed-vocab skip).

- [ ] **Step 4: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/knowledge/judge/KgMaterializationService.java \
        wikantik-ontology/src/main/java/com/wikantik/ontology/projection/EdgeProjector.java
# include the materialization test file if you added/edited a test
git commit -m "fix(kg): log guard-rejected edges (SHACL + closed-vocab) at INFO, not WARN (#4)"
```

- [ ] **Step 5: Record the deferred extractor follow-up**

The volume of out-of-vocab predicate proposals signals an extractor prompt / synonym-mapping opportunity, but the roadmap mandates **measure first**. Do NOT implement a synonym map here. Add a one-line follow-up to the SDD ledger / a `project_*` memory: "Theme B deferred — measure real out-of-vocab predicate frequency (`list_proposals` rejected reasons / prod logs) before any extractor synonym-map or prompt change."

---

### Task 3: Verification build

**Files:** none (verification only).

- [ ] **Step 1: Targeted blast-radius test run**

Run: `mvn test -pl wikantik-main -Dtest='DefaultKgProposalJudgeServiceJsonExtractTest,DefaultKgProposalJudgeServiceTest,JudgeRunnerTest,JudgeRunnerTransientUnavailableTest,KgMaterializationServiceMaterializeTest,KgMaterializationServiceNullGuardTest' -Dsurefire.failIfNoSpecifiedTests=false -q`
Expected: BUILD SUCCESS, 0 failures.

- [ ] **Step 2: Compile the ontology module (EdgeProjector change)**

Run: `mvn test -pl wikantik-ontology -q`
Expected: BUILD SUCCESS.

---

## Self-Review

**Spec coverage (vs roadmap Theme B):** #3 missing-content NPE → Task 1 Step 3 ✓; #3 parse-WARN spam → Task 1 Step 3 (demote to DEBUG) ✓; #3 fence robustness → already in `extractJsonObject`, regression test added Task 1 Step 1 ✓; #4 SHACL non-conformant WARN→INFO → Task 2 ✓; #4 closed-vocab WARN→INFO → Task 2 (EdgeProjector) ✓; #4 distinct "rejected" wording + keep counter → Task 2 Step 2 ✓; extractor synonym-map → deferred/measure-first → Task 2 Step 5 ✓.

**Placeholder scan:** none — all code shown verbatim; test class names to confirm via `ls` are flagged explicitly (KgMaterializationServiceTest / EdgeProjectorTest existence).

**Type/name consistency:** `JudgeVerdict.ABSTAIN`, `abstain(String)`, `extractJsonObject`, `skippedNonConformant` / `skippedNonConformantCount()`, `ontologyValidator.validateEdge`, `edges.upsertEdgeWithProvenance` all match the source read during planning. Abstain rationale prefix `judge_unavailable:` matches `isTransientUnavailable` in the service.
