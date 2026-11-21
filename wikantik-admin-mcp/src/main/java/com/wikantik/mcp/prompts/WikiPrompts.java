/*
    Licensed to the Apache Software Foundation (ASF) under one
    or more contributor license agreements.  See the NOTICE file
    distributed with this work for additional information
    regarding copyright ownership.  The ASF licenses this file
    to you under the Apache License, Version 2.0 (the
    "License"); you may not use this file except in compliance
    with the License.  You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing,
    software distributed under the License is distributed on an
    "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
    KIND, either express or implied.  See the License for the
    specific language governing permissions and limitations
    under the License.
 */
package com.wikantik.mcp.prompts;

import io.modelcontextprotocol.server.McpServerFeatures;

import java.util.List;
import java.util.Map;

import com.wikantik.mcp.prompts.PromptDef.Arg;

/**
 * Provides MCP Prompts for common wiki workflows, guiding AI agents through
 * structured interactions with the wiki using the read-then-write page tools.
 * Each prompt is a {@link PromptDef}: schema plus a template with named placeholders.
 */
public final class WikiPrompts {

    private WikiPrompts() {
    }

    public static List< McpServerFeatures.SyncPromptSpecification > all() {
        return DEFINITIONS.stream().map( PromptDef::toSpecification ).toList();
    }

    private static final String CREATE_ARTICLE = """
                    You are creating a new wiki article about "{topic}".

                    Follow these steps:

                    1. **Check for existing content**: On `/knowledge-mcp`, use `list_pages` with a relevant prefix, and `retrieve_context` \
                    for "{topic}" to find related pages.

                    2. **Choose a CamelCase page name**: Page names must be CamelCase with no spaces (e.g. SecurityPolicy).

                    3. **Read related pages**: Use `read_page` (admin) or `read_pages` (`/knowledge-mcp`) on any related pages \
                    so you can add cross-references.

                    4. **Draft the article**: Draft the page text (e.g. for `PageName`) \
                    with YAML frontmatter:
                       ```
                       ---
                       type: {type}
                       tags: [relevant, tags]
                       date: {today}
                       summary: one-line description (50-160 chars for SEO)
                       related: [RelatedPageName]
                       status: active
                       ---
                       # Article Title

                       Body content in Markdown.
                       ```

                    5. **Publish**: Use `write_pages` to create the new page (it only creates; it does not overwrite).

                    6. **Cross-reference**: If related pages exist, use `update_page` to add links back to the new article.

                    Use Markdown `[link text](PageName)` syntax for internal links.""";

    private static final String SUMMARIZE_TOPIC = """
                    You are researching "{topic}" across the wiki to create a comprehensive summary.

                    Follow these steps:

                    1. **Search broadly**: On `/knowledge-mcp`, use `retrieve_context` or `assemble_bundle` with query "{topic}" to find all relevant pages.

                    2. **Check metadata**: On `/knowledge-mcp`, use `list_pages_by_filter` with relevant tags or types to find categorized content.

                    3. **Read relevant pages**: Use `read_pages` (`/knowledge-mcp`) with the page names you found \
                    to read all content together and cross-reference easily.

                    4. **Follow links**: Check `get_backlinks` on key pages to discover related content you may have missed.

                    5. **Synthesize**: Combine findings into a coherent summary, noting:
                       - Key facts and findings from each page
                       - Contradictions or gaps between pages
                       - Areas that need more content

                    6. **Optionally create a summary page**: Draft a new page \
                    with type "summary" and links to all source pages in the `related` metadata field, \
                    then use `write_pages` to publish it.""";

    private static final String AUDIT_LINKS_ALL = """
                        You are auditing link integrity across the entire wiki.

                        Follow these steps:

                        1. **Get overview**: Use `get_wiki_stats` for a high-level view, then `get_broken_links` \
                        and `get_orphaned_pages` to find structural issues.

                        2. **Read for inspection**: Use `read_pages` (`/knowledge-mcp`) or `read_page` on the affected pages \
                        and check their internal links to cross-reference.

                        3. **Verify broken link targets**: For each broken link, check if it's a typo, \
                        a renamed page, or content that should be created.

                        4. **Fix issues**: Edit the page text to fix broken links, add missing links to orphaned pages.

                        5. **Apply**: Use `update_page` to apply each fix, reviewing the change with `diff_page` afterwards.

                        6. **Report findings**:
                           - Broken links (page references that don't exist)
                           - Orphaned pages (no incoming links)
                           - Pages with very few connections

                        Focus on non-system pages for the most actionable results.""";

    private static final String AUDIT_LINKS_PAGE = """
                        You are auditing link integrity for the page "{pageName}".

                        Follow these steps:

                        1. **Read the page**: Use `read_page` for "{pageName}" to get its content.

                        2. **Check links**: Use `get_outbound_links` and `get_backlinks` for "{pageName}".

                        3. **Check metadata links**: Look at the `related` field in the metadata. \
                        Verify those pages exist too.

                        4. **Fix issues**: If fixes are needed, use `read_page` on "{pageName}" and related pages, \
                        edit the text, then `update_page` to apply changes.

                        5. **Report**: List broken links, suggest fixes, and note any related pages that should link here but don't.""";

    private static final String RENAME_PAGE = """
                    You are renaming the wiki page "{oldName}" to "{newName}".

                    Follow these steps for a safe rename:

                    1. **Verify the source page exists**: Use `read_page` for "{oldName}" to confirm it exists.

                    2. **Check the target name is available**: Use `read_page` for "{newName}" to confirm no page exists with that name.

                    3. **Review incoming links**: Use `get_backlinks` for "{oldName}" to see which pages link to it. \
                    These will be updated automatically if updateLinks=true.

                    4. **Perform the rename**: Use `rename_page` with:
                       - `oldName`: "{oldName}"
                       - `newName`: "{newName}"
                       - `updateLinks`: true
                       - `confirm`: true

                    5. **Verify**: Use `read_page` for "{newName}" to confirm the page exists with correct content. \
                    Use `get_backlinks` for "{newName}" to verify referencing pages were updated.

                    Note: System pages cannot be renamed. The rename operation moves content, attachments, and version history.""";

    private static final String PUBLISH_CLUSTER = """
                    You are creating a cluster of interlinked wiki articles about "{topic}".

                    Follow these steps:

                    1. **Survey existing content**: On `/knowledge-mcp`, use `retrieve_context` with query "{topic}" and `list_metadata_values` \
                    to discover related pages and metadata conventions already in use.

                    2. **Read related pages**: Use `read_page` (admin) or `read_pages` (`/knowledge-mcp`) on any existing \
                    related pages for cross-referencing.

                    3. **Draft the pages**: Draft the text of {pageCount} pages:
                       - **Hub page** (e.g. `TopicOverview`):
                         ```
                         ---
                         type: hub
                         tags: [topic-tags]
                         date: {today}
                         summary: overview of the cluster topic
                         related: [SubArticle1, SubArticle2]
                         cluster: {slug}
                         status: active
                         ---
                         ```
                       - **Sub-articles** (e.g. `SubArticle1`):
                         ```
                         ---
                         type: article
                         tags: [topic-tags]
                         date: {today}
                         summary: one-line description
                         related: [HubPage, OtherSubArticle]
                         cluster: {slug}
                         status: active
                         ---
                         ```
                       Link hub → sub-articles and sub-articles → hub using `[text](PageName)`.

                    4. **Review**: Re-read each draft to verify the frontmatter and links look correct.

                    5. **Publish**: Use `write_pages` to create all pages in one call (create-only).

                    6. **Verify**: Use `verify_pages` on all cluster pages to confirm no broken links \
                    and metadata is complete. Use `preview_structured_data` on the hub to verify JSON-LD.""";

    private static final String EXTEND_CLUSTER = """
                    You are adding the article "{newPageName}" to the existing cluster "{clusterSlug}".

                    Follow these steps:

                    1. **Find cluster members**: On `/knowledge-mcp`, use `list_pages_by_filter` with cluster="{clusterSlug}".

                    2. **Read the cluster**: Use `read_pages` (`/knowledge-mcp`) with the cluster page names to get \
                    all existing cluster content.

                    3. **Create the new article**: Draft the page `{newPageName}` with matching metadata:
                       ```
                       ---
                       type: article
                       tags: [consistent-with-cluster]
                       date: {today}
                       summary: one-line description
                       related: [HubPage, RelevantSiblings]
                       cluster: "{clusterSlug}"
                       status: active
                       ---
                       ```

                    4. **Update existing pages**: Edit the hub page to add a link to "{newPageName}". \
                    Update `related` lists in relevant sibling articles.

                    5. **Publish**: Use `write_pages` for the new article and `update_page` for the hub and siblings.

                    6. **Verify**: Use `verify_pages` on the new article and updated pages.""";

    private static final String WIKI_HEALTH_CHECK = """
                    You are performing a comprehensive health check of the wiki.

                    Follow these steps:

                    1. **Get an overview**: Use `get_wiki_stats` to get total pages, broken link count, \
                    orphaned page count, and recent activity.

                    2. **Review broken links**: Use `get_broken_links` to find all references to non-existent pages. \
                    For each broken link, consider:
                       - Should the missing page be created?
                       - Is the link a typo that should be corrected?
                       - Has the target page been renamed without updating references?

                    3. **Find orphaned pages**: Use `get_orphaned_pages` to find pages with no incoming links.

                    4. **Check metadata quality**: Use `list_metadata_values` to review metadata fields and values. Look for:
                       - Inconsistent tag naming (e.g. "security" vs "Security")
                       - Missing type classifications
                       - Pages without tags or summaries

                    5. **Check SEO quality**: Use `verify_pages` with `checks=["seo_readiness"]` on a sample of pages.

                    6. **Fix issues**: Read affected pages with `read_page`, edit the text to fix \
                    metadata, links, and content issues, then `update_page` to apply.

                    7. **Report findings**: Summarize the wiki health status with:
                       - Overall statistics
                       - Critical issues (broken links, orphaned important pages)
                       - Recommendations for improvement
                       - Quick wins that can be fixed immediately""";

    private static final String SEO_AUDIT = """
                    You are performing an SEO readiness audit{scope}.

                    Follow these steps:

                    1. **Find pages in scope**: {findPages}

                    2. **Run SEO checks**: Use `verify_pages` with `checks=["seo_readiness"]` on all pages in scope.

                    3. **Preview impact**: For pages with warnings, use `preview_structured_data` to see \
                    meta tags, JSON-LD, feed entries, and News Sitemap eligibility.

                    4. **Fix issues**: Read affected pages with `read_page`, then edit frontmatter in the text:
                       - Add or improve summaries (aim for 50-160 characters)
                       - Add tags to enable News Sitemap inclusion
                       - Add date fields for JSON-LD datePublished
                       - Fix hub pages: ensure related lists reference existing pages

                    5. **Apply and re-verify**: Use `update_page` to apply fixes, then run \
                    `verify_pages` with `checks=["seo_readiness"]` again to confirm all issues are resolved.

                    6. **Report**: Summarize findings:
                       - Pages with good SEO (no warnings)
                       - Pages that needed attention and what was fixed
                       - News-eligible page count
                       - Any remaining issues""";

    private static final PromptDef AUDIT_LINKS = new PromptDef( "audit-links",
            "Guide checking page link integrity across the wiki",
            List.of( new Arg( "pageName", "The page to audit links for (or 'all' for wiki-wide)", false, "all" ) ),
            "{guide}",
            "Guide for auditing link integrity",
            v -> Map.of( "guide", PromptDef.fill(
                    "all".equals( v.get( "pageName" ) ) ? AUDIT_LINKS_ALL : AUDIT_LINKS_PAGE, v ) ) );

    private static final PromptDef PUBLISH_CLUSTER_DEF = new PromptDef( "publish-cluster",
            "Guide creation of a complete article cluster with hub and sub-articles",
            List.of(
                    new Arg( "topic", "The topic for the article cluster", true, "NewCluster" ),
                    new Arg( "pageCount", "Desired number of sub-articles (default: 3-5)", false, "3-5" ),
                    new Arg( "clusterSlug", "Kebab-case cluster identifier (auto-generated if omitted)", false, "" ) ),
            PUBLISH_CLUSTER,
            "Guide for creating an article cluster with hub and sub-articles",
            v -> Map.of( "slug", v.get( "clusterSlug" ).isEmpty() ? "a-kebab-case-slug" : v.get( "clusterSlug" ) ) );

    private static final PromptDef SEO_AUDIT_DEF = new PromptDef( "seo-audit",
            "Guide SEO readiness assessment across the wiki",
            List.of( new Arg( "cluster", "Optional cluster to scope the audit (omit for wiki-wide)", false, "" ) ),
            SEO_AUDIT,
            "Guide for SEO readiness assessment",
            v -> {
                final String cluster = v.get( "cluster" );
                return cluster.isEmpty()
                        ? Map.of( "scope", " across the entire wiki",
                                  "findPages", "On `/knowledge-mcp`, use `list_pages` to get all pages, "
                                          + "or `list_pages_by_filter` to find pages by type." )
                        : Map.of( "scope", " for cluster \"" + cluster + "\"",
                                  "findPages", "On `/knowledge-mcp`, use `list_pages_by_filter` with cluster=\""
                                          + cluster + "\" to find all pages in this cluster." );
            } );

    private static final List< PromptDef > DEFINITIONS = List.of(
            new PromptDef( "create-article",
                    "Guide structured article creation with proper metadata",
                    List.of(
                            new Arg( "topic", "The topic or title for the new article", true, "NewArticle" ),
                            new Arg( "type", "Article type (e.g. report, reference, note, concept)", false, "reference" ) ),
                    CREATE_ARTICLE,
                    "Guide for creating a structured wiki article" ),
            new PromptDef( "summarize-topic",
                    "Guide cross-page research and synthesis on a topic",
                    List.of( new Arg( "topic", "The topic to research and summarize", true, "the topic" ) ),
                    SUMMARIZE_TOPIC,
                    "Guide for researching and summarizing a topic across the wiki" ),
            AUDIT_LINKS,
            new PromptDef( "rename-page",
                    "Guide safe page rename workflow with link updates",
                    List.of(
                            new Arg( "oldName", "Current page name to rename", true, "OldPageName" ),
                            new Arg( "newName", "New page name (CamelCase)", true, "NewPageName" ) ),
                    RENAME_PAGE,
                    "Guide for safely renaming a wiki page" ),
            new PromptDef( "wiki-health-check",
                    "Guide comprehensive wiki health assessment",
                    List.of(),
                    WIKI_HEALTH_CHECK,
                    "Guide for comprehensive wiki health assessment" ),
            PUBLISH_CLUSTER_DEF,
            new PromptDef( "extend-cluster",
                    "Guide adding a new article to an existing cluster",
                    List.of(
                            new Arg( "clusterSlug", "Cluster identifier to extend", true, "my-cluster" ),
                            new Arg( "newPageName", "CamelCase name for the new article", true, "NewArticle" ) ),
                    EXTEND_CLUSTER,
                    "Guide for adding an article to an existing cluster" ),
            SEO_AUDIT_DEF );
}
