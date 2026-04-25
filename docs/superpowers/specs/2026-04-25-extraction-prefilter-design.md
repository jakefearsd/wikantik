# Chunk Extraction Pre-filter

A chunk-level skip predicate that runs before the LLM extraction call in the bootstrap and save-time entity-extraction paths. Drops chunks that are pure code or contain no proper-noun candidates so the extractor doesn't burn inference time on text it has nothing to extract from.

This is a standalone, opt-in feature. It is independent of any decision about which model, prompt, or extraction backend the pipeline uses — turning it on or off does not require a model swap, a schema change, or a re-chunk.

## Goals

1. Cut bootstrap-extraction wall time without changing extractor, model, or prompt.
2. Be reversible at runtime via a single config flag.
3. Be observable: report what was skipped during a run, and offer a dry-run mode that logs decisions without acting on them, so the operator can validate the filter before trusting it.
4. Apply uniformly to both the bootstrap batch (`BootstrapEntityExtractionIndexer`) and the save-time path (`AsyncEntityExtractionListener`), so a chunk that the bootstrap would skip is also skipped on incremental save.

## Non-goals

- No model, prompt, or schema changes.
- No new chunker behaviour. The chunker still emits the same chunks; the filter only decides which of those chunks reach the extractor.
- No persistence of the "skipped" decision. If the filter is later relaxed and the run re-issued with `--force`, previously-skipped chunks are re-evaluated.

## Predicates

The filter accepts a chunk's text and heading path and returns `boolean shouldExtract`. A chunk is **dropped** if any predicate fires; otherwise it proceeds to the extractor.

### 1. Pure code block

A chunk whose text is a single fenced code block (` ``` ` open, ` ``` ` close, no prose between or after) is skipped.

Heuristic: trim the text, check that it starts with ` ``` ` (with or without a language tag), contains exactly one closing ` ``` `, and that everything outside the fences is whitespace. The `ContentChunker` already classifies fenced code blocks as atomic chunks, so in practice these chunks are always one self-contained fence — the predicate is precise.

### 2. No proper-noun candidate

A chunk with no token matching `\b[A-Z]\w{2,}\b` is skipped.

Rationale: the extractor's prompt explicitly asks for "named entities … explicitly named in the chunk." A chunk with no capitalised multi-letter words has nothing for it to find. The 3-character minimum (capital + 2 word chars) excludes `Of`, `In`, `On`, etc. The post-capital class is `\w` rather than `[a-z]` so that mid-word capitals (e.g. `PostgreSQL`, `JavaScript`, `OpenAI`) are correctly matched — `[a-z]{2,}` would have failed to match the lowercase run between the leading capital and an interior capital.

False-positive cost (we filter and miss a real entity): low — entities almost always include a 3+ character capitalised token.

False-negative cost (we let a useless chunk through): low — same as today's behaviour.

The predicate runs over the chunk text only. Heading path is *not* checked: a chunk with caps only in its heading still proceeds, because the extractor sees the heading path in the user prompt and can extract from it.

## Configuration

Four properties under the existing `wikantik.knowledge.extractor.` namespace, parsed by `EntityExtractorConfig.fromProperties`:

| Property | Default | Meaning |
|---|---|---|
| `prefilter.enabled` | `false` | Master switch. When `false`, the filter is bypassed entirely. |
| `prefilter.dry_run` | `false` | When `true`, the filter logs its decision per chunk but always returns `shouldExtract=true`. Used to validate the filter against a real corpus before trusting it. |
| `prefilter.skip_pure_code` | `true` | Disable the code-block predicate independently. |
| `prefilter.skip_no_proper_noun` | `true` | Disable the proper-noun predicate independently. |

The two sub-predicate flags exist so an operator can isolate which rule is dropping a particular chunk during diagnostics, without rebuilding.

Default state on a fresh deploy: filter is **off**. An operator opts in.

## Components

### `ChunkExtractionPrefilter` (new)

Location: `wikantik-main/src/main/java/com/wikantik/knowledge/extraction/ChunkExtractionPrefilter.java`.

Pure-function class, ~40 LOC of logic. Takes the four config flags at construction, exposes one method:

```java
public Decision evaluate(String text, List<String> headingPath);

public record Decision(boolean shouldExtract, String reason) {}
```

`reason` is one of `"ok"`, `"pure_code"`, `"no_proper_noun"`, `"disabled"`, or `"dry_run"`. Used for logging and the run-summary counter, never persisted.

A "no-op" instance (`ChunkExtractionPrefilter.passthrough()`) is provided for tests and for the path where `prefilter.enabled=false` — it returns `new Decision(true, "disabled")` for every input. Callers always go through the same code path; the flag selects which instance is wired in.

### `BootstrapEntityExtractionIndexer` (modified)

`runBatch` already calls `chunkRepo.listChunkIdsForPage(page)` to get the IDs, then submits each ID to the worker pool. We add one fetch step in between:

1. Fetch chunk text for all IDs on the page via the existing `chunkRepo.findByIds(chunkIds)` (no new repo method).
2. For each `MentionableChunk`, evaluate the filter.
3. If `shouldExtract` is true, submit to the worker pool as today.
4. If false, increment `skippedChunks` and `processedChunks` counters and emit a single per-page summary log line listing the skip reasons.

The page-level fetch is one query for all chunks on the page; cost is negligible (a few KB of text per page).

Two new fields on `Status`:

```java
int skippedChunks,
Map<String, Integer> skipReasons   // reason → count
```

These appear in the per-poll progress line and the final summary.

### `AsyncEntityExtractionListener` (modified)

The save-time path runs one chunk at a time inside `runExtractionSync`. Add the same filter check at the top: if `shouldExtract` is false, return an empty `RunResult` with mention/proposal counts of zero. The chunk's existing mention rows (if any) are not touched — a chunk that *used to* extract and now gets filtered keeps its prior data. (Filter relaxation later + `--force` rebuild handles the inverse case.)

This keeps bootstrap and save-time consistent: a chunk that the bootstrap would skip on a full run is also skipped on an incremental page save.

### `EntityExtractorConfig` (modified)

Adds four fields to the record (the four properties above), parsed in `fromProperties`. The existing helpers cover `getString`/`getLong`/`getInt`/`getDouble`; this change adds a small `getBoolean` helper alongside them (treats `"true"`/`"1"`/`"yes"` case-insensitively as true, anything else as false, missing/blank as the default). Three of the four flags are booleans; one (`prefilter.dry_run`) is a boolean too. No other backend uses booleans yet, so the helper is added defensively for this feature. Construction in `BootstrapExtractionCli` and `KnowledgeGraphServiceFactory` picks them up automatically — no per-call-site change.

## Dry-run mode

When `prefilter.enabled=true` *and* `prefilter.dry_run=true`:

- The filter evaluates every chunk normally and computes a Decision.
- The Decision is logged and counted into `skipReasons`, but `shouldExtract` is forced to `true` before the caller acts on it.
- The status line then shows e.g. `skipped=0 (would-skip: pure_code=412 no_proper_noun=2031)`.

Workflow: enable dry-run, run a full bootstrap, eyeball the would-skip counts and a sampled set of skipped chunks (queried out of `kg_content_chunks` by hand if needed), then disable dry-run and re-run for real. Cost is one extra full-corpus run, but with the existing slow Gemma path that's prohibitive — so dry-run is intended to be used **after** the speed work in the larger spec lands, when a full run is cheap. Until then, dry-run can be exercised against a single page or a hand-picked subset by running the bootstrap CLI with the filter on a fresh DB containing only those pages.

A simpler and more useful immediate check: a unit test fixture that runs the filter against a representative sample of real chunks loaded from a small dump of the production DB, to validate predicate behaviour without needing a full extraction run.

## Tests

Unit tests in `ChunkExtractionPrefilterTest`:

- Pure code block (with and without language tag) → skipped.
- Code fence with prose afterwards → not skipped.
- Pure prose with no caps → skipped.
- Prose with `the iPad` → skipped (single 2-letter cap; iPad doesn't match `[A-Z][a-z]{2,}`). *Open question for review:* is this the desired behaviour? Compound caps like `iPad`, `eBay`, `gRPC` are real entities. Section below.
- Prose with `PostgreSQL` → not skipped.
- Both flags off → never skips.
- `enabled=false` → always passthrough, regardless of sub-flags.
- `dry_run=true` → returns `shouldExtract=true` even when the predicate would skip; reason field still reflects the dry-run decision.

A second test class, `BootstrapEntityExtractionIndexerPrefilterTest`, asserts:

- A page whose chunks all match a skip predicate generates zero extractor calls and one "page complete" log line.
- A mixed page calls the extractor only for the kept chunks.
- `Status.skippedChunks` and `Status.skipReasons` are populated correctly.

The existing `BootstrapEntityExtractionIndexerTest` continues to pass with `enabled=false` (default), since the filter is a passthrough in that mode.

### Open question: compound-case identifiers

The proper-noun regex `\b[A-Z][a-z]{2,}\b` does not match `iPad`, `eBay`, `gRPC`, `kubectl`, etc. — common names in technical content. Three options:

1. Accept the false-negative. These chunks are skipped. Matches the documented prompt rule that the extractor wants "explicitly named" entities, which compound-case names usually are alongside a regular proper noun ("PostgreSQL's pgvector extension") — the regular noun saves the chunk.
2. Broaden the regex to `\b[A-Za-z]*[A-Z][a-z]{2,}\b` to catch leading-lowercase compounds.
3. Add a small allowlist of known compound names that fire the predicate.

Recommend option 1 for v1. The cost of skipping a chunk is one missing extraction; the gain is a simple, predictable predicate. Revisit if dry-run shows a surprising volume of compound-only chunks.

## Operational behaviour

- Filter decisions are logged at `DEBUG` per chunk and summarised at `INFO` per page (`page='X' kept=12/17 skipped: pure_code=2 no_proper_noun=3`).
- `Status.skippedChunks` flows into the existing `BootstrapExtractionCli` progress line and `AdminExtractionResource` JSON status.
- No metric emission in v1 (the existing pipeline doesn't emit Prometheus counters per chunk; sticking to the same shape).

## Build and ship

- One commit. No migration. No frontend change. No documentation page change beyond a single sentence under `EntityExtractorConfig` in `CLAUDE.md` if the operator-facing config table there needs it (it does not currently list every flag, so probably no edit).
- Default state is off. Switching it on is a property edit + restart.

## Why this is genuinely independent of the speed/model decision

- The filter sits at the boundary between "list of chunks to extract" and "extractor call." It does not see the extractor, the model, the prompt, or the proposal pipeline.
- Both Ollama (current) and Claude (existing alt-backend) extractors get the same speedup proportional to the skip rate.
- A future GLiNER-sidecar path would re-use the same filter with no change.
- Disabling it is a one-property revert.

The number reported in the brainstorming session ("30-40% drop") is a guess. The actual rate on this corpus is unknown until dry-run reports it. The design's value is delivering a measurable, configurable, reversible knob — not the magnitude of the wins.
