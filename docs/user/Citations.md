# Citations

This guide is for authors who want one page to cite a specific passage of another page, and for editors who want to know when such a citation has gone out of date. It covers the markup, how the wiki grades each citation, and where stale ones show up.

Citation tracking is on by default (`wikantik.citations.enabled = true`) and needs the database. When it is off, `cite://` links are not parsed or tracked.

## Write a citation

A citation is a Markdown link whose address starts with `cite://`:

```markdown
[Bread needs a long, cold proof](cite://01HZX4ABCDEF/Baking/Proofing "overnight in the refrigerator")
```

The parts are:

| Part | Meaning |
|---|---|
| `[Bread needs ...]` | Your claim: the sentence in your page that the citation supports. |
| `cite://01HZX4ABCDEF` | The target page, identified by its `canonical_id` from frontmatter (not by page name, so renaming the target does not break the citation). |
| `/Baking/Proofing` | Optional heading path in the target. Each segment is a heading, outermost first, URL-encoded (`%20` for a space). Leave it off to cite the whole page. |
| `"overnight in the refrigerator"` | Optional span: the exact words you are relying on, in the link title. |

Syntax limits: the span cannot contain a double quote (`"`), and the target and heading path cannot contain `)` or whitespace (URL-encode them, for example `%20`). The claim cannot contain `]`. A citation that breaks these rules is not recognised as a citation.

Whitespace in the span is collapsed when it is compared, and the comparison is case-sensitive.

## Staleness grades

When you save a page, the wiki records each citation with the version of the target it saw and grades it. It re-grades the citations that point at a page whenever that page is saved, and a full re-grade runs after each search-index rebuild to catch anything missed.

| Grade | Meaning |
|---|---|
| `current` | The target and the cited heading exist, and the span (if you gave one) still appears in that section. A citation with no span is `current` as long as the heading exists. |
| `stale` | The target exists but the cited heading is gone, or the span no longer appears in that section. The passage changed or moved. |
| `target_missing` | The target page cannot be found or has no body. |

## What readers see

Readers see an ordinary link to the target page, with an anchor to the heading when you gave a heading path. The span you quoted is not shown on hover, and staleness is never shown to readers. A `cite://` address that matches no page renders as a dead link.

## Find stale citations

Staleness surfaces for curators, not readers:

- `GET /admin/drift/citations`: the administrator drift report. It returns counts by grade for the whole wiki, and with `?page=<canonical_id>` lists that page's outgoing citations and the non-current citations pointing at it.
- The `list_stale_citations` tool on the knowledge MCP endpoint.
- The `stale_citations` section of the for-agent page projection (`GET /api/pages/for-agent/{id}`).

To repair one, open the citing page, re-read the target's current section, and update the claim and span (or the heading path). Saving re-grades it.

## See also

- [Links and embeds](Linking.md): ordinary wiki and external links.
- [Frontmatter](Frontmatter.md): where `canonical_id` is defined.
