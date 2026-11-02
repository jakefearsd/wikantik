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

Run 2026-11-01 against the live production page store (docker1, prod at 2.5.5), before the 2.5.6 deploy. The stored
`.md` files were copied read-only out of the running container (`docker exec repo-wikantik-1 tar cf - *.md` from
`/var/wikantik/pages`). That gives byte-exact stored text, frontmatter quoting included, so `--dir` mode was used
rather than `--url`. The `--url` mode rebuilds frontmatter from parsed fields.

```
java -cp "$CP" com.wikantik.extractcli.AclShapeAuditCli --dir <prod-page-copy> --format text
```

Summary: **pages 1466, audited 1466, fetch failures 0, locked 0, changed 0, ESCAPED 0** (exit 0).

13 prod pages contain `[{ALLOW`/`[{DENY` text in some spelling:

- **Real access rules (3 pages):** `CredentialsLocator`, `RejectedMessage` and `WikantikConfigurationReference`. The
  new reader enforces the same ACL as both old readers.
- **Documentation only (the other 10):** the text appears inside code spans or fences, for example
  `` `[{DENY ...}]` `` in `WikantikSecurityGuide` and `` `[{ALLOW}]`/`[{DENY}]` `` in `NativeWikilinksDesign`. The
  text is literal for all three readers, and it stays hidden from callers without `edit`.

No production page needed an edit. The deploy prerequisite (zero locked prod pages) is met. Re-run this audit after
the deploy to confirm the server-side `accessRules` reading agrees, using `--url` with an Admin-group key.
