---
status: active
date: '2026-09-29'
summary: Design for a scoped, permission-gated export that turns a slice of the wiki
  into a ready-to-open Obsidian vault zip.
tags:
- wikantik
- export
- obsidian
- design
type: design
canonical_id: 01M3R82K0G89V20K3M07K8PETZ
cluster: wikantik-development
related:
- WikantikDevelopmentHub
title: 'Obsidian-Compatible Content Export Design: Scoped Vault Archive Generation'
---
# Obsidian-Compatible Content Export — Design

> **Status (2026-10-13):** Shipped in v2.4.29 (2026-10-01) as designed, behind the `export` wiki permission (migration V060). One deferred item has since shipped: the inverse direction, importing a vault into the wiki, is [Obsidian Vault Import](ObsidianVaultImportDesign) (v2.4.50), and export also understands the native `[[wikilinks]]` and `![[embeds]]` added by [Native Wikilinks](NativeWikilinksDesign). Heading anchors in exported links now match the page view's slugs (v2.4.29), so links to headings containing `_`, `&` or non-ASCII characters resolve; vaults exported earlier keep their old anchors.

Canonical spec: `docs/superpowers/specs/2026-09-29-obsidian-export-design.md`; plan: `docs/superpowers/plans/2026-09-29-obsidian-export.md`. Part of the [Wikantik Platform Development Hub](WikantikDevelopmentHub).

## Problem

Readers cannot take wiki content offline. Existing raw surfaces are per-page (`/wiki/{slug}?format=md`), the `/api/changes` feed, and whole-ontology RDF dumps. Even a hand-built folder of `.md` files does not work in Obsidian: wiki links are `[Text](WikiName)`, anchors are slugs rather than heading text, attachments use `Page/file.ext`, and wiki plugin markup is meaningless outside the wiki.

## Decisions

- **Delivery:** one-shot zip now; a manifest ships from day one so an incremental refresh tool can follow.
- **Scope control:** clusters (with sub-clusters) plus frontmatter filters (tags, type, status), with optional outbound-link hop expansion of 0–2. A live preview shows page count, attachments, size and unresolved links before download.
- **Out-of-scope links:** per-export setting — keep as unresolved Obsidian wikilinks (default) or rewrite to live wiki URLs.
- **Permission:** a new `export` wiki permission, granted to Authenticated by default (migration V060) and revocable in `policy_grants`. The migration only touches the Authenticated grant row if it still holds the stock defaults.
- **Architecture:** synchronous streaming `ZipOutputStream` behind `GET /api/export`, plus `GET /api/export/preview` and `GET /api/export/options`. No job state. Selection and conversion live in `com.wikantik.export` in wikantik-main so a future CLI or MCP tool can reuse them.

## Conversion

Pages are parsed with Flexmark and only the source spans of nodes that need rewriting are replaced; every other byte is copied verbatim, so code blocks and formatting survive.

- Internal links become Obsidian wikilinks (targeting the file name actually written) with the original text as the display alias.
- Slug anchors are resolved against the target page's headings, because Obsidian links by heading text.
- Attachments are written under `_attachments/<Page>/` and linked as embeds (images) or wikilinks (other files).
- Citation links become heading links with the cited span as a footnote.
- ACL and variable directives and the table-of-contents plugin are stripped; InsertPage becomes an Obsidian embed; any other plugin becomes a visible callout pointing at the live page.
- Frontmatter is patched textually (dates stay dates), with `wikantik_url`, `wikantik_version`, merged `aliases` and a wikilink-form `related` added.

## Access control

Every decision uses the exporting user's view permission. Restricted pages are dropped before hop expansion, so the export never follows links out of a page the user cannot see, and out-of-export heading and citation lookups use the same session.

## Archive

Pages go under their primary cluster folder (sub-clusters nested, unclustered pages in `_unclustered/`), with filenames sanitised for Windows and macOS and case collisions suffixed. Path segments are neutralised and the zip writer rejects any entry that could escape the vault root. There is no `.obsidian/` directory, so unzipping into an existing vault never overwrites settings. `.wikantik/manifest.json` records the selection, server, timestamp, and per-page canonical id, version, path and SHA-256. A readme at the vault root states what was selected, what was stripped, and any warnings.

## Limits and failure handling

Selection, ACL filtering and the page cap (`wikantik.export.maxPages`, default 2000; over-cap is a 413 with the true count) complete before the first byte is streamed. Concurrent downloads are capped by `wikantik.export.maxConcurrent` (default 2; over the cap is a 429). After streaming starts nothing aborts: a page that fails conversion is written raw and an unreadable attachment is skipped, each recorded as a warning in the manifest and readme.

## UI

An export dialog (a modal, not a route) opens from the user menu and from an "Export this cluster" button on hub pages. It previews the result live as filters change and downloads via a plain link so the browser handles the transfer.

## Deferred

Incremental refresh CLI, MCP export tool, page-history export, Obsidian-to-wiki import (since shipped, see the status note), and case collisions between attachment folders.
