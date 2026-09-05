# Configuration Surface Design

**Status:** accepted 2026-09-05
**Plan:** `docs/superpowers/plans/2026-09-05-configuration-surface.md`

## Problem

Production Java reads 267 distinct `wikantik.*` property keys. The shipped
defaults file `wikantik-main/src/main/resources/ini/wikantik.properties`
declares 97 of them explicitly, 48 only as commented-out examples, and 122
not at all. A further 60 keys in that file are read by nothing (dead docs or
examples of dynamic-prefix keys). One key has two different code defaults
depending on the wiring path. The MCP surface has a second file
(`wikantik-mcp.properties`, 9 `mcp.*` keys, 4 undeclared). An administrator
reading the shipped file learns about a third of the surface, and nothing
stops the gap from widening.

## Decisions

1. **The defaults file is the reference, and it is complete.** Every
   `wikantik.*` key any production module reads appears in
   `ini/wikantik.properties` exactly once, **uncommented, with its effective
   default value spelled out**, even when that value is the code's own
   fallback. The same rule applies to `mcp.*` keys in
   `wikantik-admin-mcp/src/main/resources/wikantik-mcp.properties`.
   Commented-out `#key = value` lines are banned: a key is either declared or
   deleted.

2. **Every entry carries a structured description.** The comment block
   immediately above a key is its documentation and is machine-parsed:

   ```
   #
   #  One or more description lines. Say what the setting does and when an
   #  admin would change it.
   #  Type: boolean
   wikantik.ontology.enabled = true
   ```

   Required lines: description (at least one line that is not a directive)
   and `Type:`. Types: `boolean`, `int`, `long`, `double`, `string`, `path`,
   `url`, `class`, `list`, `enum(a|b|c)`, `secret`. Optional directives:
   `Blank means:` (**required** when the value is blank), `Source:`
   (`properties` default; `system-property` for keys such as
   `wikantik.scim.token` that are read only from `-D`/init-params, so the
   file entry is documentation, not a live default). Sections are declared
   with a `# [Section Name]` marker line; every entry belongs to one.

3. **Secrets never ship a default.** `Type: secret` entries must be blank in
   the defaults file.

4. **Blank means unset, and readers must honour it.** Declaring `key =`
   puts `""` into `Properties` where the reader previously saw `null`. Every
   reader of a blank-default key must treat blank as absent
   (`isBlank()`), verified by a unit test at the reader.

5. **A drift test enforces all of the above from source.**
   `ConfigSurfaceDriftTest` (wikantik-war, next to `TestSchemaSingleSourceTest`)
   scans `wikantik-*/src/main/java` for `"wikantik.…"` literals (comments
   stripped), subtracts an explicit, self-pruning `NOT_CONFIG` allow-list
   (metric names, request attributes, file names), accepts keys under
   registered `DYNAMIC_PREFIXES` (e.g. `wikantik.interWikiRef.`), and fails on:
   a code key absent from the file, a file key no code reads, a commented-out
   key, a missing description/Type/section, a value that does not parse for
   its type, a blank value without `Blank means:`, a non-blank secret, and
   (phase 2) a literal code default that differs from the file value except
   where `KNOWN_DIVERGENT` records a reason.

6. **Burn-down by ratchet, never a red gate.** The test lands with a
   baseline file `build-support/config-surface-baseline.tsv` listing today's
   violations. New violations fail; fixed violations that are still listed
   also fail, so the file can only shrink. The baseline is deleted when
   empty.

7. **Documentation is generated from the file, never hand-maintained.**
   `GenerateConfigReferenceCli` (wikantik-extract-cli, same shape as
   `GenerateMainPageCli`) renders `docs/ConfigurationReference.md` and the
   wiki page `docs/wikantik-pages/WikantikConfigurationReference.md`
   (cluster `wikantik-development`) from the two properties files, including
   an accurate precedence section derived from `PropertyReader` and
   `TextUtil`. `ConfigReferenceRegressionTest` fails when the committed
   output is stale. `bin/config-reference.sh --write` regenerates.

8. **Deliberate divergences stay, documented.**
   `wikantik.search.dense.backend` defaults to `lucene-hnsw` on the wiring
   path and `inmemory` on the no-DataSource fallback path, pinned by
   `DenseBackendResolutionTest`. The file declares `lucene-hnsw` and the
   description names the fallback. `KNOWN_DIVERGENT` carries the reason.

## Out of scope

- Replacing string-keyed property reads with a typed registry (a later
  ADR if the drift test proves insufficient).
- Changing precedence semantics in `PropertyReader`/`TextUtil`. The
  generated doc describes them as they are, including the case-sensitivity
  of environment-variable overrides.
- The test-resource copies of `ini/wikantik.properties` under
  `wikantik-main/src/test/resources` and siblings. They are fixtures.
