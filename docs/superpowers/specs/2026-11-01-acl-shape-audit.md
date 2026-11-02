# Access-rule shape audit (#104)

Design: [2026-11-01-pre-wysiwyg-ui-and-security-design.md](2026-11-01-pre-wysiwyg-ui-and-security-design.md) section 5.4.

## Method

1. **Detector.** `AclShapeAuditCli` (`wikantik-extract-cli`) runs `AccessRules.read` over every page and prints, per page:
   the reading (`Rules` / `Locked` with reasons), the ACL the old text scan enforced, the ACL the old render path
   enforced, the new ACL, and `changed` (new ACL differs from either old ACL). The summary lists the locked, changed and
   ESCAPED pages. An ACL is the sorted set of `permission:principal` grants (`LOCKED` for a locked page).
2. **Old readers.** Both are still in the tree, so nothing was resurrected from git. The old text scan is
   `AclDirectiveScanner.findAll(text, false)` minus spans in Markdown code (a CLI-only copy of the few lines of the
   package-private `EnforcedAclDirectives`); the old render path is the public `RenderPathAccessRules.find`, whose rule
   lines the old `parseAcl` read by skipping the first word, so a `DENY` counts as a grant there.
3. **Inputs.** Repo corpus: `--dir docs/wikantik-pages`, plus the `RepoCorpusAccessRulesTest` unit test (zero locked
   pages over `docs/wikantik-pages/` and `wikantik-wikipages/**`; the deliberately locked
   `wikantik-it-tests/**/test-repo/LockedAccessRulesFixture.md` is outside both roots). Production: `--url` as an
   administrator (see Production below).
4. **Fix rules** (design 5.4 rule 2): write the canonical shape that expresses the author's evident intent; where intent
   is not expressible (`DENY`) take the narrower of the two old readings and say so; documentation that merely mentions a
   directive gets it put in a code span.
5. **Escaped directives.** ESCAPED pages are listed explicitly: the new reader reads `\[{ALLOW ...}]` as literal text,
   so a page that relied on the old text scan reading it as a rule becomes more open. Each needs a decision.

Limits of `--url`: `GET /api/pages/{name}` returns frontmatter as parsed fields, so the audited text is rebuilt (YAML dump
of the fields, then the body). Body shapes are exact; a directive inside a frontmatter value is seen but its original
quoting is not. A page the server locks that the rebuilt text does not is reported locked with the server's reasons.

## Repo corpus result

Command: `AclShapeAuditCli --dir docs/wikantik-pages` (exit 0), 2026-11-01:

```
== Summary ==
pages: 1197  audited: 1197  fetch failures: 0
locked: 0
changed: 0
ESCAPED: 0
fetch failures: 0
```

`RepoCorpusAccessRulesTest` passes: no shipped page (`docs/wikantik-pages`, `wikantik-wikipages`) is locked. No repo
page needed a fix, so no content commit.

## Production

_To be filled in by the controller: command run, summary (locked / changed / ESCAPED / fetch failures), then one entry
per finding with page, span, old text-scan ACL, old render ACL, new ACL, the fix and why._
