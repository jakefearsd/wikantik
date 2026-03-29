# JSPWiki Development News

A log of recent development activity on the JSPWiki project.

---

## March 2026

**2026-03-29** — Make username the Preferences link, remove separate Preferences button

**2026-03-29** — Intercept internal wiki links for React Router navigation

**2026-03-29** — Improve 403 error message in UserFormModal to suggest re-login

**2026-03-29** — Add autocomplete attributes to login form for browser credential suggestions

**2026-03-29** — Make tags in MetadataPanel clickable search links too

**2026-03-29** — Make tags clickable search links for cross-article navigation

**2026-03-28** — Fix diff viewer: API returns 'diff' field, not 'diffHtml'

**2026-03-28** — Re-fetch page data when auth state changes to update permission buttons

**2026-03-28** — Add explicit credentials: 'same-origin' to fetch calls

**2026-03-28** — Revert changeSessionId() — breaks SessionMonitor session tracking

**2026-03-28** — Fix login broken by session fixation prevention: re-register WikiSession after changeSessionId()

**2026-03-28** — Add JSP dead code catalog: 228 files identified for removal after React migration

**2026-03-28** — Add user preferences and lost password recovery

**2026-03-28** — Add comments system: REST endpoint + React panel

**2026-03-28** — Add page rename: REST endpoint + React modal

**2026-03-28** — Add diff/version comparison viewer to React SPA

**2026-03-28** — Add conflict resolution modal to page editor

**2026-03-28** — Add page delete button with confirmation modal to React SPA

**2026-03-28** — Add implementation plan: JSP to React migration (8 tasks)

**2026-03-28** — Add design spec: JSP to React migration — complete UI feature parity

**2026-03-28** — Add comprehensive plugin test coverage: 8 plugins, 74 new tests

**2026-03-28** — Improve wikantik-main test coverage: auth, render, groups, JDBC

**2026-03-28** — Extract invalidateCaches() in PageDirectoryWatcher to eliminate duplication

**2026-03-28** — Push wikantik-rest toward 80%: restricted group names, nonexistent groups, version purge with history, bulk delete verification, search frontmatter

**2026-03-28** — Improve wikantik-rest test coverage with meaningful assertions

**2026-03-28** — Improve wikantik-util test coverage with meaningful assertions

**2026-03-28** — Improve wikantik-mcp test coverage from 74.6% to 81.6% with meaningful assertions

**2026-03-28** — Add CacheInfo tests and EhcacheCachingManager coverage improvements

**2026-03-28** — Add WikiEvent base class and WikiEventManager coverage tests

**2026-03-28** — Add AdminUserResource and AdminContentResource tests — wikantik-rest coverage improvement

**2026-03-28** — Add tests for FormUtil, XhtmlUtil, XHTML, PropertiesUtils — wikantik-util coverage improvement

**2026-03-28** — Add event module tests: WikiSecurityEvent, WikiPageEvent, WikiEngineEvent, WikiPageRenameEvent

**2026-03-28** — Add SpaRoutingFilter tests: static asset passthrough, SPA route forwarding

**2026-03-28** — Add AdminAuthFilter tests: OPTIONS passthrough, 403 for non-admin, valid JSON response

**2026-03-28** — Add comprehensive tests for all 9 OWASP security header filters

**2026-03-28** — Add test coverage: search permissions, SessionMonitor, CSRF admin exemption, attachment ACLs, frontmatter edge cases, DatabasePolicy errors, version conflicts, concurrency, CORS, bootstrap

**2026-03-28** — Security hardening: CSP, session fixation, CORS restriction, error sanitization

**2026-03-28** — Refactor MCP tools: extract methods to reduce cyclomatic complexity

**2026-03-28** — Fix SpotBugs encoding, null-check, naming, and mutable-collection issues

**2026-03-28** — Fix SpotBugs encoding, null-check, naming, and mutable-collection issues

**2026-03-28** — Replace SessionMonitor WeakHashMap with ConcurrentHashMap for lock-free session lookup

**2026-03-28** — Optimize Lucene search: skip full permission check for pages without ACLs

**2026-03-28** — Update 6 docs to reflect current system capabilities

**2026-03-28** — Update README and CLAUDE.md to reflect current capabilities

**2026-03-28** — Scalability: increase cache capacity/TTL, replace synchronized with ReadWriteLock

**2026-03-28** — Include effective permissions in page API response, gate Edit button on actual permission

**2026-03-28** — Show Edit/Create buttons for all users, not just authenticated

**2026-03-28** — Fix AllPermission checkbox: style as a clear toggle control, not orphaned checkbox

**2026-03-28** — Fix policy grants table: align Manage column and constrain Actions width

**2026-03-28** — Fix CSRF filter blocking admin POST/PUT/DELETE requests

**2026-03-28** — Add Security tab to admin panel navigation and routing

**2026-03-28** — Add AdminSecurityPage with Groups and Policy Grants sub-sections

**2026-03-28** — Add PolicyGrantFormModal with context-sensitive action checkboxes

**2026-03-28** — Add GroupFormModal component for create/edit group

**2026-03-28** — Add implementation plan: admin security UI

**2026-03-28** — Add design spec: admin security UI for groups and policy grants

**2026-03-28** — Update config template and deploy script for database-backed permissions

**2026-03-28** — Add admin REST endpoints for groups and policy grants, integrate DatabasePolicy into DefaultAuthorizationManager

**2026-03-28** — Add DatabasePolicy: database-backed policy provider for authorization

**2026-03-28** — Guard Admin group: block deletion and empty-member saves

**2026-03-28** — Add DDL migration for policy_grants table with default seed data

**2026-03-28** — Add implementation plan: database-backed permissions

**2026-03-28** — Spec update: block removal of last Admin group member in code

**2026-03-28** — Add design spec: database-backed permissions and group management

**2026-03-27** — Security: add ObjectInputFilter whitelist to block unsafe deserialization

**2026-03-27** — Security: enforce ACL/permission checks on REST API endpoints

**2026-03-27** — Security: enforce ACL/permission checks on REST API endpoints

**2026-03-27** — Update News page with recent development entries

**2026-03-27** — Add NIST 800-63B password strength validation for user account creation

**2026-03-27** — Security hardening: IP-restrict observability endpoints, sanitize health error messages, fix async filter support

**2026-03-27** — Add wikantik-observability module: health checks, Prometheus metrics, structured logging, request correlation

**2026-03-27** — Admin panel: user management, content management, and infrastructure improvements

**2026-03-26** — Article authoring UX: new article wizard, frontmatter editing, change history

**2026-03-26** — Filter system pages from all referral plugins — finally done!

**2026-03-26** — Update News page with recent development entries + remove test artifacts

**2026-03-26** — Add search index rebuild guide for local and Docker deployments

**2026-03-26** — Clickable metadata chips with Lucene frontmatter indexing

**2026-03-26** — Sidebar UX: move sign-in + theme toggle between logo and search, remove Pages section

**2026-03-25** — Fix React SPA link routing for plugin-rendered content

**2026-03-25** — Mobile sidebar UX, dev seed, and CLAUDE.md docs

**2026-03-25** — Remove legacy JSPWiki artifacts superseded by Markdown migration

**2026-03-25** — Update React sidebar to match JSP LeftMenu navigation structure

**2026-03-25** — Bundle React SPA into WAR — served at /app/ from same Tomcat

**2026-03-25** — Add React frontend — editorial magazine aesthetic with full read/write

**2026-03-25** — REST API: add render option, version retrieval, metadata PATCH

**2026-03-25** — REST API: add attachments, diff, recent changes, outbound links endpoints

**2026-03-25** — Add REST API integration tests -- Cargo-based HTTP validation

**2026-03-24** — REST API Phases 2-3: search, history, backlinks, login/logout

**2026-03-24** — Add wikantik-rest module — REST/JSON API for alternative frontends (Phase 1)

**2026-03-24** — Upgrade Selenide from 7.12.1 to 7.13.0

**2026-03-24** — Revert "Merge pull request #20 from jakefearsd/dependabot/maven/selenide.version-7.15.0"

**2026-03-24** — Merge pull request #20 from jakefearsd/dependabot/maven/selenide.version-7.15.0

**2026-03-24** — Merge pull request #17 from jakefearsd/dependabot/maven/pac4j.version-6.3.3

**2026-03-24** — Merge pull request #19 from jakefearsd/dependabot/maven/commons-net-commons-net-3.13.0

**2026-03-24** — Merge pull request #18 from jakefearsd/dependabot/maven/org.apache.maven.plugins-maven-pmd-plugin-3.28.0

**2026-03-24** — Bump org.apache.maven.plugins:maven-pmd-plugin from 3.26.0 to 3.28.0

**2026-03-24** — Rename single-letter local variables to descriptive names across 33 files

**2026-03-24** — Add Maven site generation — reports, coverage, and per-module navigation

**2026-03-24** — Fix encapsulation violations in wikantik-mcp — use Engine interface, remove SitemapServlet import

**2026-03-24** — Add wikantik-cache-memcached module — distributed cache adapter

**2026-03-24** — Convert 124 remaining LOG string concatenations to parameterized placeholders

**2026-03-24** — Improve logging: convert string concatenation to parameterized placeholders in 10 files

**2026-03-24** — Add MCP audit tools and wiki audit skill design specs

**2026-03-23** — Improve readability: rename single-letter vars, use log placeholders, cleanup

**2026-03-23** — Update ADR-001 to reflect completed implementation + add production architecture doc

**2026-03-23** — Move PageSaveHelper, SaveOptions, VersionConflictException to wikantik-api

**2026-03-23** — Move FrontmatterParser, FrontmatterWriter, ParsedPage, MarkdownLinkScanner to wikantik-api

**2026-03-23** — Convert 11 more MCP tests from TestEngine to stubs

**2026-03-23** — Decouple 13 MCP tool constructors from WikiEngine

**2026-03-23** — Add StubPageManagerTest and test stub conversion plan

**2026-03-23** — Extract 8 manager interfaces from wikantik-main to wikantik-api (ADR-001)

**2026-03-23** — Fix test-jar configuration so stubs are available to MCP tests

**2026-03-23** — test: create StubReferenceManager and convert 7 MEDIUM MCP tests from TestEngine to stubs

**2026-03-23** — test: convert 11 MCP tool tests from TestEngine to StubPageManager

**2026-03-23** — Bump selenide.version from 7.12.1 to 7.15.0

**2026-03-23** — Bump commons-net:commons-net from 3.12.0 to 3.13.0

**2026-03-23** — Bump pac4j.version from 6.3.1 to 6.3.3

**2026-03-22** — Add ADR-001: Extract manager interfaces to wikantik-api

**2026-03-22** — Apply 6 Gang of Four design patterns across core and MCP modules

**2026-03-22** — Sync DB init scripts to staging before deploy

**2026-03-22** — Fix Dockerfile: replace non-existent wikantik-markdown with wikantik-http

**2026-03-22** — Update News page content

**2026-03-22** — Merge pull request #15 from jakefearsd/dependabot/maven/org.codelibs-nekohtml-3.0.3

**2026-03-22** — Merge pull request #16 from jakefearsd/dependabot/maven/mcp-sdk.version-1.1.0

**2026-03-22** — Merge pull request #14 from jakefearsd/dependabot/maven/org.apache.maven.plugins-maven-war-plugin-3.5.1

**2026-03-22** — Merge pull request #13 from jakefearsd/dependabot/maven/org.apache.maven.plugins-maven-dependency-plugin-3.10.0

**2026-03-22** — Add staging deploy workflow for local Docker deployment

**2026-03-22** — Replace full Markdown render with regex link extraction in ReferenceManager

**2026-03-22** — Add git pull step to CI/CD and step-by-step setup guide

**2026-03-22** — Add Docker containerization: Dockerfile, Compose, backup system

**2026-03-22** — Update wiki content: new articles, metadata improvements, and fixes

**2026-03-22** — Update skills, audit reports, and gitignore

**2026-03-22** — Replace legacy CI with self-hosted runner CI/CD pipeline

**2026-03-22** — Add Atom feed servlet and update sitemap with news extension

**2026-03-22** — Add MCP audit, SEO, and cluster management tools with tests

**2026-03-22** — Add test coverage for MetadataOperations, RecentArticlesServlet

**2026-03-22** — Extract page validation checks into Strategy pattern (GoF)

**2026-03-22** — Remove dead JSPWiki syntax handling — Markdown is the only parser

**2026-03-22** — Optimize string handling: hoist Pattern.compile, eliminate regex in hot path

**2026-03-22** — Fix mailto link classification, code block heading detection, and redundant splits

**2026-03-22** — Fix wiki variable rendering when variable is sole child of paragraph

**2026-03-21** — Fix corrupted page and add MCP write guard against serialized JSON responses

**2026-03-21** — Fix concurrency bugs, null safety, dead code, and encoding issues from SpotBugs/PMD audit

**2026-03-21** — Reduce test suite runtime: consolidate duplicates, optimize JDBC tests, remove 3 IT modules

**2026-03-21** — Performance, MCP hardening, and IT test quality improvements

**2026-03-20** — Fix canonical URLs to be fully qualified and harden SemanticWebIT for Cargo

**2026-03-20** — Clean up remaining legacy wiki-syntax references after Markdown migration

**2026-03-20** — Complete Markdown migration: remove legacy wiki-syntax parser and collapse modules

**2026-03-19** — Fix SystemInfo page: proper Markdown tables and updated variable names

**2026-03-19** — Migrate to Markdown-only rendering as the default parser

**2026-03-19** — Reduce duplication in MCP tools and JDBC database classes

**2026-03-19** — Update News page content

**2026-03-19** — Replace Apache feather logo with wikαntik text logo

**2026-03-19** — Rebrand Phase 9: documentation cleanup and final sweep

**2026-03-19** — Rebrand Phase 8: Docker, deployment, and infrastructure

**2026-03-19** — Rebrand Phase 7: visual assets and UI text

**2026-03-19** — Rebrand Phase 6: Java package rename org.apache.wiki → com.wikantik

**2026-03-19** — Rebrand Phase 5: module directory renames and Maven coordinates

**2026-03-19** — Rebrand Phases 1-4: identity, config prefix, resource renames, taglib URI

**2026-03-19** — Remove .mcp.json — leaked API key

**2026-03-19** — Bump mcp-sdk.version from 1.0.0 to 1.1.0

**2026-03-19** — Bump org.codelibs:nekohtml from 2.1.3 to 3.0.3

**2026-03-19** — Bump org.apache.maven.plugins:maven-war-plugin from 3.4.0 to 3.5.1

**2026-03-19** — Bump org.apache.maven.plugins:maven-dependency-plugin

**2026-03-18** — Add warehouse-automation cluster pages to jspwiki-pages

**2026-03-18** — Merge pull request #12 from jakefearsd/dependabot/maven/org.apache.maven.plugins-maven-resources-plugin-3.5.0

**2026-03-18** — Point wiki-article-cluster skill at production MCP endpoint

**2026-03-18** — Merge pull request #10 from jakefearsd/dependabot/maven/org.apache.maven.plugins-maven-jar-plugin-3.5.0

**2026-03-18** — Merge pull request #9 from jakefearsd/dependabot/maven/jakarta.xml.bind-jakarta.xml.bind-api-4.0.5

**2026-03-18** — Merge pull request #6 from jakefearsd/dependabot/maven/mockito.version-5.21.0

**2026-03-18** — Fix MCP transport type for Claude Code compatibility

**2026-03-18** — Add rebrand project plan and update research history

**2026-03-18** — Bump version to 3.1.2: sync Release.java with Maven POM version

**2026-03-18** — Force MCP write_page to always save as Markdown, never .txt

**2026-03-18** — Fix Operations Research articles: rename .txt to .md for Markdown rendering

**2026-03-18** — Fix Main.md: restore Technology cluster links and separate Operations Research section

**2026-03-18** — Add Operations Research cluster and update existing wiki pages

**2026-03-17** — Add Mathematical Foundations of Machine Learning to Technology cluster

**2026-03-17** — Add Embedded AI on Limited Hardware article to Technology cluster

**2026-03-17** — Add Technology cluster articles: LLMs, Future of ML, Foundational Algorithms

**2026-03-17** — Update News.md with latest commit entry

**2026-03-16** — Fix NewsPageGenerator breaking unit tests and add MCP markupSyntax test coverage

**2026-03-16** — Add first-deployment tests for NewsPageGenerator and fix --no-pager bug

**2026-03-16** — Bump org.apache.maven.plugins:maven-resources-plugin from 3.4.0 to 3.5.0

**2026-03-16** — Add NewsPageGenerator to auto-generate News.md from git history

**2026-03-15** — Add News page with development log from last 2 months

**2026-03-15** — Add version-controlled wiki pages: 113 pages including 22 new articles

**2026-03-15** — Move wiki pages to docs/jspwiki-pages/ for version control

**2026-03-15** — Add markupSyntax parameter to write_page MCP tool and fix file extension handling

**2026-03-15** — Bump version to 3.1.1-SNAPSHOT for next development cycle

**2026-03-15** — Release 3.1.0: bump version across all modules

**2026-03-15** — Fix MCP backlinks, sync ReferenceManager init, update IT tests

**2026-03-15** — Bump MCP server version to 2.0.0 with production polish

**2026-03-15** — Move skill to .claude/, update retirement docs, fix table styling and markdown test

**2026-03-15** — Harden MCP endpoint security: multi-key auth, rate limiting, audit logging

**2026-03-15** — Bump mockito.version from 5.20.0 to 5.21.0

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

**2026-02-18** — Bump org.apache.maven.plugins:maven-jar-plugin from 3.4.2 to 3.5.0

**2026-02-18** — Bump jakarta.xml.bind:jakarta.xml.bind-api from 4.0.4 to 4.0.5

**2026-02-17** — Align logging-mailhandler with Jakarta Mail 2.x namespace

**2026-02-17** — Add Single Sign-On (SSO) support via OIDC and SAML using pac4j 6.3.1

**2026-02-16** — Implement PageProvider.getAllChangedSince() and push date filtering to the data source

**2026-02-16** — Add WatchService-based filesystem watcher for external content publishing


---

## December 2025

**2025-12-24** — Add backward compatibility for legacy Outcome field names

**2025-12-22** — Merge branch 'master' of https://github.com/jakefearsd/jspwiki

**2025-12-22** — Add workflow approval support for wiki group management

**2025-12-22** — Add workflow approval support for wiki group management

**2025-12-14** — Fix Outcome deserialization causing NPE on startup

**2025-12-14** — Fix incorrect and unclear FIXME comments

**2025-12-14** — Remove unused plainUris field and PROP_PLAINURIS constant

**2025-12-14** — Remove unused templateExists() method from TemplateManager

**2025-12-14** — Remove Hungarian notation (c_ prefix) from static fields

**2025-12-14** — Remove Hungarian notation (m_ prefix) from WikiEngine

**2025-12-14** — Remove Hungarian notation (m_ prefix) from WikiSession

**2025-12-14** — Remove Hungarian notation (m_ prefix) from WikiPage and WikiContext

**2025-12-14** — Remove Hungarian notation (m_ prefix) from DefaultReferenceManager

**2025-12-14** — Remove Hungarian notation (m_ prefix) from WatchDog

**2025-12-14** — Remove Hungarian notation (m_ prefix) from WikiBackgroundThread

**2025-12-14** — Remove Hungarian notation (m_ prefix) from misc utility classes

**2025-12-13** — Remove Hungarian notation (m_ prefix) from tags package

**2025-12-13** — Remove Hungarian notation (m_ prefix) from parser package

**2025-12-13** — Remove Hungarian notation (m_ prefix) from ui package

**2025-12-13** — Remove Hungarian notation (m_ prefix) from workflow package

**2025-12-13** — Remove Hungarian notation (m_ prefix) from auth package

**2025-12-13** — Remove Hungarian notation from auth/authorize package

**2025-12-13** — Remove Hungarian notation from ui/admin package

**2025-12-13** — Remove Hungarian notation from auth/permissions package

**2025-12-13** — Remove Hungarian notation from auth/acl package

**2025-12-13** — Remove Hungarian notation from management package

**2025-12-13** — Remove Hungarian notation from plugin package

**2025-12-13** — Remove Hungarian notation from attachment package

**2025-12-13** — Remove Hungarian notation from providers package

**2025-12-13** — Remove Hungarian notation from url package

**2025-12-13** — Remove Hungarian notation from render package

**2025-12-13** — Remove Hungarian notation from search package

**2025-12-13** — Added class I forgot to include from an earlier refactor.

**2025-12-13** — Remove Hungarian notation from diff package

**2025-12-13** — Remove Hungarian notation from pages package

**2025-12-13** — Remove Hungarian notation from content package

**2025-12-13** — Remove Hungarian notation from references package

**2025-12-13** — Remove Hungarian notation from filters, modules, and ui/progress packages

**2025-12-13** — Remove Hungarian notation from auth/user and tasks packages

**2025-12-13** — Remove Hungarian notation from ajax and variables packages

**2025-12-13** — Remove Hungarian notation from forms package

**2025-12-13** — Remove Hungarian notation (m_ prefix) from field names - first increment

**2025-12-12** — Reduce cyclomatic complexity in high-complexity methods

**2025-12-11** — Apply JDK 21 records and sealed classes modernizations

**2025-12-11** — Apply JDK 21 modernizations and fix authorization for non-JSPWiki permissions

**2025-12-11** — Modernize Java APIs: StringBuffer, Vector, SimpleDateFormat, and logging

**2025-12-11** — Modernize deprecated Java APIs and improve JDK 21 compatibility

**2025-12-11** — Add JDBCPlugin for executing SQL queries and displaying results as HTML tables

**2025-12-09** — Add TTL-based refresh for CachingProvider and RecentArticlesTemplate

**2025-12-09** — Add RecentArticles plugin and REST API for displaying recent wiki articles

**2025-12-08** — Document critical integration test parallelism constraint

**2025-12-08** — Simplify WikiEngine initialization and improve code clarity

**2025-12-08** — Optimize WikiEngine startup with parallel initialization and fix JNDI context issue

**2025-12-08** — Prepare for next development iteration 3.0.7-SNAPSHOT

**2025-12-08** — Release 3.0.6

**2025-12-07** — Fix race condition in CachingProvider.getAllPages() causing incomplete sitemap

**2025-12-07** — Add Cloudflare access logging support to Tomcat configuration

**2025-12-07** — Fix startup race condition in authorization manager

**2025-12-07** — Prepare for next development iteration 3.0.6-SNAPSHOT

**2025-12-07** — Release 3.0.5

**2025-12-07** — Update SLF4J binding from 1.x to 2.x for compatibility

**2025-12-07** — Remove Log4j 1.x bridge dependency and add external logging configuration

**2025-12-03** — Add 60-second cache TTL to enable filesystem-based article updates

**2025-12-03** — Apply Gang of Four design patterns for improved architecture

**2025-12-02** — Release 3.0.2

**2025-12-02** — Fix flaky LuceneSearchProviderTest.testGetIndexedPageNamesReturnsMultiplePages

**2025-12-02** — Optimize page metadata and ACL handling for better performance

**2025-12-02** — Add SEO improvements for better search engine indexing

**2025-12-01** — Fix Preferences reloading properties on every HTTP request

**2025-12-01** — Add missing page detection to Lucene indexer and clean up build

**2025-12-01** — Add docs copying to deploy-local.sh

**2025-12-01** — Rename docs to WikiCase format for wiki compatibility

**2025-12-01** — Add tests for new pages appearing in UnusedPages

**2025-12-01** — Fix UnusedPages not listing newly added pages

**2025-12-01** — Add email configuration documentation


---

## November 2025

**2025-11-30** — Add PostgreSQL local deployment support and fix empty search query error

**2025-11-30** — Longer term planning documents updated.

**2025-11-30** — Update PostgreSQL DDL for compatibility with PostgreSQL 15-18

**2025-11-30** — Update favicons with revised JF monogram design

**2025-11-30** — Bump version to 3.0.1

**2025-11-30** — Fix favicon paths to use absolute URLs via wiki:Link tag

**2025-11-29** — Add JF monogram source SVG to docs

**2025-11-29** — Replace favicons with JF monogram branding

**2025-11-29** — Improve custom properties file discovery with better logging

**2025-11-29** — Add INFO logging for SitemapServlet base URL configuration

**2025-11-29** — Fix jspwiki-portable build phase ordering (MDEP-98)

**2025-11-29** — Add configurable base URL for sitemap generation

**2025-11-29** — Release JSPWiki 3.0.0

**2025-11-29** — Add comprehensive observability system design document

**2025-11-29** — Add robots.txt for search engine crawler guidance

**2025-11-29** — Optimize sitemap.xml for Google: remove ignored fields, add image extension

**2025-11-29** — Creating a plan for OAuth implementation, that along with the sitemap.xml functionality should be the biggest wins for unlocking ease of use for this site.

**2025-11-28** — Remove deprecated Creole wiki markup support

**2025-11-28** — Remove incomplete Finnish (fi) localization

**2025-11-27** — Modernize instanceof checks with pattern matching (Java 16+)

**2025-11-27** — Modernize local variable declarations with var keyword

**2025-11-27** — Override jspc-maven-plugin to use Tomcat 11 jasper

**2025-11-27** — Implement Tier 1 performance optimizations

**2025-11-27** — Update stable dependency versions

**2025-11-27** — Fix HttpMockFactory to support servlet context attributes

**2025-11-27** — Migrate from EhCache 2.10.9.2 to EhCache 3.10.8

**2025-11-27** — Upgrade Lucene from 9.12.3 to 10.1.0

**2025-11-27** — Upgrade minimum Java version from 17 to 21

**2025-11-27** — Update minor dependency versions

**2025-11-26** — Refactor XML database classes to use shared XmlDomUtil utility

**2025-11-26** — Refactor JSPWikiMarkupParser by extracting handler classes

**2025-11-26** — Add comprehensive test coverage for JSPWikiMarkupParser

**2025-11-26** — Add edge case tests for XMLUserDatabase and JDBCUserDatabase

**2025-11-26** — Modernize Date/Time API usage in CalendarTag and CreoleToJSPWikiTranslator

**2025-11-25** — Updated several simple dependencies.

**2025-11-24** — Remove Apache ORO dependency from pom.xml files

**2025-11-24** — Replace Apache ORO regex library with Java built-in regex

**2025-11-24** — Modernize legacy collections and remove deprecated constructors

**2025-11-23** — Update dependencies: commons-lang3 3.20.0, commons-io 2.21.0, Selenide 7.12.0

**2025-11-23** — Fix PropertyReader warning for Log4j2 lookup syntax

**2025-11-23** — Fix flaky AnonymousViewIT.anonymousReaderView test

**2025-11-23** — Update Log4j2 to 2.25.2 and Lucene to 9.12.3

**2025-11-23** — Remove Chinese (zh_CN) wiki pages module

**2025-11-23** — Add SitemapServlet configuration to all IT test modules

**2025-11-23** — Remove optional search providers and deprecated WikiEngine accessors

**2025-11-23** — Remove Chinese (zh_CN) language support

**2025-11-23** — Remove orphaned RPC/Atom/RSS references from codebase

**2025-11-23** — Remove legacy 210 template to simplify codebase

**2025-11-23** — Remove XML-RPC, 2.10 Adapters, and RSS/Atom/Weblog features

**2025-11-23** — New feature description that needs refinement to be completed.

**2025-11-23** — Implement SitemapServlet for search engine indexing

**2025-11-23** — Updated this war to drive a local deployment for integration testing.

**2025-11-23** — Updated the plan for sitemap to include changes for how URLs are now generated.

**2025-11-23** — Add documentation for JSPWiki Markdown internal link syntax

**2025-11-23** — This is a simple update to keep testing running after failure so that my coding agents get more test failure feedback when something does go wrong.

**2025-11-23** — This is the plan for the sitemap implementation that I will try executing with help from Claude Code.

**2025-11-23** — Updating tests to make them more robust in the face latency for integration tests.  This is all to help use the ShortViewURLConstructor to make URLs cleaner in the build up to adding a proper sitemap function to the wiki for search indexing.

**2025-11-22** — Fix ACL deprecation warnings by migrating to new API interfaces

**2025-11-22** — Fix deprecation warnings across multiple modules

**2025-11-22** — Fix flaky integration tests in Selenide page objects

**2025-11-22** — Fix deprecation warnings in TextUtil

**2025-11-22** — Fix deprecation warning in ClassUtil

**2025-11-22** — Fix deprecation warnings in comparator classes

**2025-11-22** — Fix flaky integration test in searchFor method

**2025-11-22** — Fix flaky integration test in clickOnShowReaderView

**2025-11-22** — Upgrade Cargo Maven plugin to use Tomcat 11.0.14

**2025-11-22** — Add local configuration and documentation files

**2025-11-22** — This update adds the ability to prefer Markdown pages when they are present on the disk, but lacks a good way of adding the links. Rendering is tested and seems to work well both in automation and manually by downloading Google Docs as Markdown and pasting them onto the file system they are correctly rendered by the wiki.

**2025-11-13** — Merge pull request #437 from spyhunter99/feature/JSPWIKI-1220

**2025-11-13** — fixes a typo in the change log

**2025-11-13** — Merge pull request #421 from spyhunter99/feature/JSPWIKI-1220

**2025-11-13** — updates the change log and release version

**2025-11-13** — Merge branch 'master' into feature/JSPWIKI-1220

**2025-11-13** — Merge pull request #422 from spyhunter99/feature/JSPWIKI-1129

**2025-11-13** — JSPWIKI-1220 fixes the absolute urls for atom feeds

**2025-11-13** — JSPWIKI-1220 should fix the url validation issue

**2025-11-13** — should fix the build

**2025-11-13** — docker config is ssl only now

**2025-11-13** — this should address the docker configuration. unfortunately i don't have a way to test this

**2025-11-13** — Update jspwiki-main/src/main/resources/ini/jspwiki.properties

**2025-11-13** — Merge pull request #428 from spyhunter99/feature/securityImprovements

**2025-11-13** — Merge pull request #432 from spyhunter99/feature/JSPWIKI-1237

**2025-11-13** — Merge pull request #435 from spyhunter99/bug/JSPWIKI-1238-i18n2

**2025-11-13** — Merge pull request #436 from spyhunter99/JSPWIKI-388

**2025-11-13** — compile fix for unit tests

**2025-11-13** — Merge branch 'master' into JSPWIKI-388

**2025-11-13** — adds documentation notes

**2025-11-13** — SPWIKI-1238 addresses the missing i18n keys for all existing resource files and adds a unit test to complain about missing stuff in the future

**2025-11-12** — JSPWIKI-1237 adds the owasp recommended http response headers

**2025-11-11** — fixes the build, typo

**2025-11-11** — Merge pull request #423 from spyhunter99/build/enableItnegrationTests

**2025-11-11** — Merge pull request #427 from spyhunter99/feature/updateTomcat

**2025-11-11** — JSPWIKI-1230   Preconfigured tomcat configurations should disable autoDeploy, unpackWARs, showReport and showServerInfo by default also implements the following security improvements on both the portable build and the docker configuration V-222979 sets tomcat's session timeout to 10 minutes V-223009 tomcat Connector address attribute must be set. V-223005 ENFORCE_ENCODING_IN_GET_WRITER must be set to true. V-223003 RECYCLE_FACADES must be set to true. V-222957 xpoweredBy attribute must be disabled. V-222956 Autodeploy must be disabled. V-222955 The deployXML attribute must be set to false in hosted environments. V-222951 The shutdown port must be disabled. V-222950 Stack tracing must be disabled. V-222977 ErrorReportValve showReport must be set to false. V-222975 ErrorReportValve showServerInfo must be set to false.

**2025-11-11** — bump tomcat tot he latest of v10

**2025-11-09** — NOJIRA enables the integration test profile for all our CI jobs, skips selenium based tests on windows

**2025-11-09** — JSPWIKI-1129 adds some more checks for setting cookies server side

**2025-11-08** — JSPWIKI-1129 bumps up the security on all cookies that are set by jspwiki, which is effectively just user preferences. Portable server is now TLS only

**2025-11-08** — JSPWIKI-1220 replaces the sandler library with the rome rss/atom support library JSPWIKI-1223 restores the /atom servlet api's capabilities, restores the ability to create and store weblog entries with the requisite attribute for the atom feed to work JSPWIKI-1225 fixes loading the preloaded data for the portable build JSPWIKI-1224 rss.jsp now sets the filename for download JSPWIKI-1226 related, marks FIXME's for all the rss link generation that needs updating and updates the change log from today

**2025-11-08** — Merge pull request #420 from spyhunter99/github_actions JSPWIKI-1179

**2025-11-08** — disables jdk23 and 24 as they also fail for the same reason as 25

**2025-11-08** — updates the mvn commands

**2025-11-08** — fixes the compile issue and updates the github actions for additional build profiles

**2025-11-08** — Merge remote-tracking branch 'upstream/master' into github_actions

**2025-11-08** — Merge pull request #419 from spyhunter99/master

**2025-11-08** — 3.0.0-git-04 updates the changelog and release file

**2025-11-08** — Merge pull request #414 from spyhunter99/feature/JSPWIKI-1218

**2025-11-08** — Merge pull request #418 from spyhunter99/build/jdomRollback

**2025-11-08** — slightly newer version of the nekohtml parser

**2025-11-08** — whitespace to retrigger jenkins

**2025-11-07** — and rolling back debug output during unit tests

**2025-11-07** — NOJIRA rolls back the jdom dependency update. CI had green lights before merging, but it's definitely broke the build

**2025-11-07** — Merge branch 'master' of https://github.com/apache/jspwiki

**2025-11-07** — Merge branch 'master' into feature/JSPWIKI-1218

**2025-11-07** — Merge pull request #159 from apache/dependabot/maven/org.jdom-jdom2-2.0.6.1

**2025-11-07** — JSPWIKI-1218 updates the dependencies for Akismet. Confirmed working against their service

**2025-11-07** — fixes the change log

**2025-11-07** — Merge branch 'master' of https://github.com/apache/jspwiki

**2025-11-07** — Merge branch 'master' of https://github.com/apache/jspwiki

**2025-11-07** — Merge pull request #416 from spyhunter99/master

**2025-11-07** — Bump jdom2 from 2.0.6 to 2.0.6.1

**2025-11-07** — JSPWIKI-1218 removes the dependency for http commons client from 3.x to 5. Users of the captcha.jsp file may have some issues caused by this JSPWIKI-1219 SpamFilter Capcha capability via asirra.com is dead, removes all references to the asirra.com based spam filter (was discontinued in 2015)

**2025-11-07** — JSPWIKI-1218 updates the dependency for http commons client from 3.x to 5.x. Oddly it doesn't seem like it's used anywhere. i'll try removing it entirely on the next commit

**2025-11-07** — 3.0.0-git-03

**2025-11-07** — Merge pull request #400 from apache/dependabot/maven/jakarta.mail-jakarta.mail-api-2.1.5

**2025-11-07** — Merge pull request #410 from spyhunter99/feature/JSPWIKI-1183-if-with-ip-check

**2025-11-07** — Merge branch 'master' into feature/JSPWIKI-1183-if-with-ip-check

**2025-11-07** — Merge pull request #412 from spyhunter99/bug/JSPWIKI-1216

**2025-11-07** — Merge pull request #413 from apache/bug/disableSecondRssTest

**2025-11-07** — JSPWIKI-1183 another attempt to remove the duplicate test and get the ci builds working again

**2025-11-07** — Merge pull request #404 from spyhunter99/bug/JSPWIKI-1207

**2025-11-07** — Merge pull request #408 from spyhunter99/bug/JSPWIKI-1211

**2025-11-07** — Merge branch 'master' into feature/JSPWIKI-1183-if-with-ip-check

**2025-11-07** — Merge pull request #411 from spyhunter99/feature/JSPWIKI-615-docs

**2025-11-07** — Merge pull request #409 from spyhunter99/feature/JSPWIKI-1213

**2025-11-07** — temporarily turn on unit test output to diagnosis ci failures

**2025-11-07** — temporarily turn on unit test output to diagnosis ci failures

**2025-11-07** — temporarily turn on unit test output to diagnosis ci failures

**2025-11-07** — JSPWIKI-1183 potential fix for the rss generator test failure when running in parallel. adds JDK25 check for security manager related behavior change

**2025-11-07** — JSPWIKI-1183 potential fix for the rss generator test failure when running in parallel. adds JDK25 check for security manager related behavior change

**2025-11-07** — JSPWIKI-1183 potential fix for the rss generator test failure when running in parallel

**2025-11-07** — JSPWIKI-1217 should resolve the maven parallel build failure on the ci server.

**2025-11-01** — JSPWIKI-1216 removes references to the WikiWizard template/editor types. adds a test to ensure that there are no missing I18N strings among all the i18n properties files, none were found


---

## October 2025

**2025-10-29** — typo

**2025-10-29** — JSPWIKI-615 adds some javadocs to the WikiEvent class as requested

**2025-10-29** — JSPWIKI-1183 switches to commons-net to keep inline with apache guidance

**2025-10-29** — JSPWIKI-1183 applies the suggestion change and patch file as submitted

**2025-10-29** — JSPWIKI-1211 adds notes for the rss path calculation

**2025-10-29** — JSPWIKI-1213 adds jacoco to the build

**2025-10-27** — JSPWIKI-1211 minor tweak to fix a bootup issue when the rss directory path does not exist

**2025-10-22** — JSPWIKI-1207 temporary fix for the ehcache causing bootup crashes on the portable builds

**2025-10-01** — Bump jakarta.mail:jakarta.mail-api from 2.1.3 to 2.1.5


---

## September 2025

**2025-09-30** — 3.0.0-git-02

**2025-09-30** — Update dependencies

**2025-09-30** — Update wro4j maven plugin to 2.1.1 + fix typo preventing including polyfills.js to be processed by wro4j

**2025-09-30** — Update dependencies

**2025-09-30** — remove extra whitespace

**2025-09-30** — Remove deprecated libraries usage + use some jdk-17 constructs

