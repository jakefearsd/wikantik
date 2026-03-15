# JSPWiki Development News

A log of recent development activity on the JSPWiki project.

---

## March 2026

**2026-03-15** — Release 3.1.0: bump version across all modules

**2026-03-15** — Bump MCP server version to 2.0.0 with production polish

**2026-03-15** — Harden MCP endpoint security: multi-key auth, rate limiting, audit logging

**2026-03-15** — Fix MCP backlinks, sync ReferenceManager init, update IT tests

**2026-03-15** — Add markupSyntax parameter to write_page MCP tool and fix file extension handling

**2026-03-15** — Move wiki pages to docs/jspwiki-pages/ for version control

**2026-03-15** — Add version-controlled wiki pages: 113 pages including 22 new articles

**2026-03-15** — Move skill to .claude/, update retirement docs, fix table styling and markdown test

**2026-03-15** — Add Monte Carlo retirement planning article to cluster

**2026-03-15** — Update Main wiki page, upgrade 9 dependencies (all tests pass)

**2026-03-14** — Add cluster identifiers to article metadata and export 48 cluster pages

**2026-03-14** — Restructure README with docs/ navigation index and fix stale content

**2026-03-14** — Update wiki-article-cluster skill for new MCP tools and publish 2 articles

**2026-03-14** — Simplify MCP interface: fix Markdown link tracking, add PageSaveHelper, new MCP tools

**2026-03-14** — Add FundamentalsOfProgramming article and document MCP improvement observations

**2026-03-14** — Add Linux for Windows Users cluster (8 pages) and document in research history

**2026-03-14** — Add Spousal Green Card Guide cluster (8 pages) and document in research history

**2026-03-14** — Refine wiki-article-cluster skill based on 4 clusters of real usage

**2026-03-14** — Add semantic wiki design notes and superpowers plan archive

**2026-03-14** — Add research history for personal finance article cluster

**2026-03-14** — Add MCP attachment tools, new prompts, and wire all Phase 2 features (Phase 3)

**2026-03-14** — Add MCP page rename, locking, and completion providers (Phase 2)

**2026-03-14** — Add MCP link graph and wiki health tools (Phase 1)

**2026-03-14** — Add research history documenting MCP article publishing workflow

**2026-03-14** — Add MCP API helper script for manual tool calls

**2026-03-14** — Fix Markdown link ordering bug and remove incompatible MCP outputSchema

**2026-03-14** — MCP API redesign: metadata merge, author attribution, resources, prompts, and 4 new tools

**2026-03-13** — Fix 17 SpotBugs defects: mutable statics, NPE guards, and dead code

**2026-03-13** — Fix 14 high-priority PMD defects: overridable constructors, unsealed classes, null return

**2026-03-13** — Fix 3 code quality defects: null-return APIs, overridable constructor call, ignored return value

**2026-03-13** — Extract McpToolUtils to reduce boilerplate across all MCP tool classes

**2026-03-13** — Remove redundant textToHTML from save path and refactor AbstractReferralPlugin

**2026-03-13** — Add PMD plugin and fix 3 high-priority defects it identified

**2026-03-13** — Fix 4 SpotBugs correctness defects with regression tests

**2026-03-13** — Add centralized SystemPageRegistry, AliasPlugin, and filter system pages from listings

**2026-03-13** — Add MCP access control filter, restore deleted plugins, fix IndexPlugin sort bug

**2026-03-13** — Move frontmatter classes to jspwiki-main, strip frontmatter in Markdown renderer, and fix MCP pages saving as wiki syntax instead of Markdown

**2026-03-13** — Fix MCP async servlet support by enabling async on all wildcard filters

**2026-03-13** — Restore RecentArticles plugin and tests removed in f4ec85056

**2026-03-12** — Fix MCP integration tests, improve test runtime, and enforce failure reporting

**2026-03-12** — Add 60-test MCP integration test suite exercising full protocol stack

**2026-03-12** — Add MCP server module for AI-powered wiki interaction

**2026-03-11** — Fix flaky test suite: Lucene directory race, WikiEventManager global shutdown, parallel test isolation

**2026-03-11** — Remove accidentally committed files (cloudflare config, vim swap)

**2026-03-11** — Remove ~46K lines of legacy code: WYSIWYG, workflow engine, portable dist, low-value plugins, excess i18n

**2026-03-10** — Send admin email notification on new user registration

**2026-03-10** — Add .worktrees to .gitignore

---

## February 2026

**2026-02-18** — Add commented-out Google Workspace OIDC example to SSO configuration

**2026-02-17** — Align logging-mailhandler with Jakarta Mail 2.x namespace

**2026-02-17** — Add Single Sign-On (SSO) support via OIDC and SAML using pac4j 6.3.1

**2026-02-16** — Implement PageProvider.getAllChangedSince() and push date filtering to the data source

**2026-02-16** — Add WatchService-based filesystem watcher for external content publishing
