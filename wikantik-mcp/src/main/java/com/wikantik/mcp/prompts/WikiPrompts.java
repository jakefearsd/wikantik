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
import io.modelcontextprotocol.spec.McpSchema;

import java.util.List;
import java.util.Map;

/**
 * Provides MCP Prompts for common wiki workflows, guiding AI agents through
 * structured interactions with the wiki.
 */
public final class WikiPrompts {

    private WikiPrompts() {
    }

    public static List< McpServerFeatures.SyncPromptSpecification > all() {
        return List.of( createArticle(), summarizeTopic(), auditLinks(), renamePage(), wikiHealthCheck(),
                publishCluster(), extendCluster(), seoAudit() );
    }

    private static McpServerFeatures.SyncPromptSpecification createArticle() {
        final McpSchema.Prompt prompt = new McpSchema.Prompt(
                "create-article",
                "Guide structured article creation with proper metadata",
                List.of(
                        new McpSchema.PromptArgument( "topic", "The topic or title for the new article", true ),
                        new McpSchema.PromptArgument( "type", "Article type (e.g. report, reference, note, concept)", false )
                )
        );

        return new McpServerFeatures.SyncPromptSpecification( prompt, ( exchange, request ) -> {
            final Map< String, Object > args = request.arguments() != null ? request.arguments() : Map.of();
            final String topic = args.getOrDefault( "topic", "NewArticle" ).toString();
            final String type = args.getOrDefault( "type", "reference" ).toString();

            final String guide = String.format( """
                    You are creating a new wiki article about "%s".

                    Follow these steps:

                    1. **Check for existing content**: Use `list_pages` with a relevant prefix, and `search_pages` for "%s" to find related pages.

                    2. **Choose a CamelCase page name**: Page names must be CamelCase with no spaces (e.g. SecurityPolicy, ProjectAlpha).

                    3. **Write the article** using `write_page` with:
                       - `content`: The Markdown body of the article
                       - `metadata`: Include at minimum:
                         - `type: %s`
                         - `tags: [relevant, tags]`
                         - `date: %s` (today's date)
                         - `summary: one-line description` (50-160 chars — this becomes the meta description in search results)
                         - `related: [RelatedPageName]` (link to pages found in step 1)
                       Include tags — these enable Google News Sitemap inclusion.
                       - `changeNote`: "Initial creation"

                    4. **Verify**: Use `read_page` to confirm the article was saved correctly.

                    5. **Cross-reference**: If related pages exist, consider updating them to link back to the new article.

                    Use Markdown `[link text](PageName)` syntax for internal links.""",
                    topic, topic, type, java.time.LocalDate.now().toString() );

            return new McpSchema.GetPromptResult(
                    "Guide for creating a structured wiki article",
                    List.of( new McpSchema.PromptMessage(
                            McpSchema.Role.USER,
                            new McpSchema.TextContent( guide ) ) ) );
        } );
    }

    private static McpServerFeatures.SyncPromptSpecification summarizeTopic() {
        final McpSchema.Prompt prompt = new McpSchema.Prompt(
                "summarize-topic",
                "Guide cross-page research and synthesis on a topic",
                List.of(
                        new McpSchema.PromptArgument( "topic", "The topic to research and summarize", true )
                )
        );

        return new McpServerFeatures.SyncPromptSpecification( prompt, ( exchange, request ) -> {
            final Map< String, Object > args = request.arguments() != null ? request.arguments() : Map.of();
            final String topic = args.getOrDefault( "topic", "the topic" ).toString();

            final String guide = String.format( """
                    You are researching "%s" across the wiki to create a comprehensive summary.

                    Follow these steps:

                    1. **Search broadly**: Use `search_pages` with query "%s" to find all relevant pages.

                    2. **Check metadata**: Use `query_metadata` with relevant tags or types to find categorized content.

                    3. **Read key pages**: Use `read_page` on each relevant result to understand the content.

                    4. **Follow links**: Check `get_backlinks` on key pages to discover related content you may have missed.

                    5. **Synthesize**: Combine findings into a coherent summary, noting:
                       - Key facts and findings from each page
                       - Contradictions or gaps between pages
                       - Areas that need more content

                    6. **Optionally create a summary page**: If the user wants, use `write_page` to create a synthesis page with type "summary" and links to all source pages in the `related` metadata field.""",
                    topic, topic );

            return new McpSchema.GetPromptResult(
                    "Guide for researching and summarizing a topic across the wiki",
                    List.of( new McpSchema.PromptMessage(
                            McpSchema.Role.USER,
                            new McpSchema.TextContent( guide ) ) ) );
        } );
    }

    private static McpServerFeatures.SyncPromptSpecification auditLinks() {
        final McpSchema.Prompt prompt = new McpSchema.Prompt(
                "audit-links",
                "Guide checking page link integrity across the wiki",
                List.of(
                        new McpSchema.PromptArgument( "pageName", "The page to audit links for (or 'all' for wiki-wide)", false )
                )
        );

        return new McpServerFeatures.SyncPromptSpecification( prompt, ( exchange, request ) -> {
            final Map< String, Object > args = request.arguments() != null ? request.arguments() : Map.of();
            final String pageName = args.getOrDefault( "pageName", "all" ).toString();

            final String guide;
            if ( "all".equals( pageName ) ) {
                guide = """
                        You are auditing link integrity across the entire wiki.

                        Follow these steps:

                        1. **List all pages**: Use `list_pages` to get the full page inventory.

                        2. **For each page**: Use `read_page` to read the content and extract all internal links (Markdown `[text](PageName)` format).

                        3. **Verify targets exist**: For each linked page name, check if it exists using `read_page`. Note any broken links where `exists` is false.

                        4. **Check orphaned pages**: Use `get_backlinks` on each page. Pages with zero backlinks may be orphaned.

                        5. **Report findings**:
                           - Broken links (page references that don't exist)
                           - Orphaned pages (no incoming links)
                           - Pages with very few connections

                        Focus on non-system pages for the most actionable results.""";
            } else {
                guide = String.format( """
                        You are auditing link integrity for the page "%s".

                        Follow these steps:

                        1. **Read the page**: Use `read_page` for "%s" to get its content.

                        2. **Extract links**: Find all internal links in the Markdown content (format: `[text](PageName)`).

                        3. **Verify each link target**: Use `read_page` for each linked page name. Note any where `exists` is false.

                        4. **Check backlinks**: Use `get_backlinks` for "%s" to see what links TO this page.

                        5. **Check metadata links**: Look at the `related` field in the metadata. Verify those pages exist too.

                        6. **Report**: List broken links, suggest fixes, and note any related pages that should link here but don't.""",
                        pageName, pageName, pageName );
            }

            return new McpSchema.GetPromptResult(
                    "Guide for auditing link integrity",
                    List.of( new McpSchema.PromptMessage(
                            McpSchema.Role.USER,
                            new McpSchema.TextContent( guide ) ) ) );
        } );
    }

    private static McpServerFeatures.SyncPromptSpecification renamePage() {
        final McpSchema.Prompt prompt = new McpSchema.Prompt(
                "rename-page",
                "Guide safe page rename workflow with link updates",
                List.of(
                        new McpSchema.PromptArgument( "oldName", "Current page name to rename", true ),
                        new McpSchema.PromptArgument( "newName", "New page name (CamelCase)", true )
                )
        );

        return new McpServerFeatures.SyncPromptSpecification( prompt, ( exchange, request ) -> {
            final Map< String, Object > args = request.arguments() != null ? request.arguments() : Map.of();
            final String oldName = args.getOrDefault( "oldName", "OldPageName" ).toString();
            final String newName = args.getOrDefault( "newName", "NewPageName" ).toString();

            final String guide = String.format( """
                    You are renaming the wiki page "%s" to "%s".

                    Follow these steps for a safe rename:

                    1. **Verify the source page exists**: Use `read_page` for "%s" to confirm it exists and review its content.

                    2. **Check the target name is available**: Use `read_page` for "%s" to confirm no page exists with that name.

                    3. **Review incoming links**: Use `get_backlinks` for "%s" to see which pages link to it. These will be updated automatically if updateLinks=true.

                    4. **Review outbound links**: Use `get_outbound_links` for "%s" to understand what the page connects to.

                    5. **Perform the rename**: Use `rename_page` with:
                       - `oldName`: "%s"
                       - `newName`: "%s"
                       - `updateLinks`: true (to update all referencing pages)
                       - `confirm`: true

                    6. **Verify the result**: Use `read_page` for "%s" to confirm the page exists with correct content.

                    7. **Check links updated**: Use `get_backlinks` for "%s" to verify pages now reference the new name.

                    Note: System pages cannot be renamed. The rename operation moves page content, attachments, and version history.""",
                    oldName, newName, oldName, newName, oldName, oldName, oldName, newName, newName, newName );

            return new McpSchema.GetPromptResult(
                    "Guide for safely renaming a wiki page",
                    List.of( new McpSchema.PromptMessage(
                            McpSchema.Role.USER,
                            new McpSchema.TextContent( guide ) ) ) );
        } );
    }

    private static McpServerFeatures.SyncPromptSpecification publishCluster() {
        final McpSchema.Prompt prompt = new McpSchema.Prompt(
                "publish-cluster",
                "Guide creation of a complete article cluster with hub and sub-articles",
                List.of(
                        new McpSchema.PromptArgument( "topic", "The topic for the article cluster", true ),
                        new McpSchema.PromptArgument( "pageCount", "Desired number of sub-articles (default: 3-5)", false ),
                        new McpSchema.PromptArgument( "clusterSlug", "Kebab-case cluster identifier (auto-generated if omitted)", false )
                )
        );

        return new McpServerFeatures.SyncPromptSpecification( prompt, ( exchange, request ) -> {
            final Map< String, Object > args = request.arguments() != null ? request.arguments() : Map.of();
            final String topic = args.getOrDefault( "topic", "NewCluster" ).toString();
            final String pageCount = args.getOrDefault( "pageCount", "3-5" ).toString();
            final String clusterSlug = args.getOrDefault( "clusterSlug", "" ).toString();
            final String today = java.time.LocalDate.now().toString();

            final String guide = String.format( """
                    You are creating a cluster of interlinked wiki articles about "%s".

                    Follow these steps:

                    1. **Survey existing content**: Use `search_pages` with query "%s" and `list_metadata_values` \
                    to discover related pages and metadata conventions already in use.

                    2. **Design the cluster**: Plan a hub page and %s sub-articles. All page names must be CamelCase. \
                    %sDesign the inter-page link structure: hub links to all sub-articles, sub-articles link back to hub.

                    3. **Publish pages**: Use `batch_write_pages` to create all pages. Write the hub page first. \
                    Each page must include full metadata:
                       - `type`: "hub" for the hub page, "article" for sub-articles
                       - `tags`: relevant topic tags (consistent across all cluster pages)
                       - `date`: %s
                       - `related`: list of related CamelCase page names within the cluster
                       - `cluster`: %s
                       - `status`: "active"
                       - `summary`: one-line description
                       - `author`: set to a descriptive name, not "MCP"
                       - `changeNote`: "Initial creation"

                    4. **Set cross-references**: Use `batch_update_metadata` to set the `related` field across all cluster pages \
                    so each page references its siblings and the hub.

                    5. **Verify integrity**: Use `verify_pages` on all cluster pages to confirm:
                       - All pages exist
                       - No broken links
                       - All pages have backlinks
                       - Metadata is complete

                    5b. **Verify SEO**: Use `preview_structured_data` on the hub and one sub-article to verify \
                    JSON-LD, meta tags, and feed entries. Hub should show CollectionPage with hasPart.

                    6. **Document**: Append cluster details to `docs/research_history.md` including topic, all pages created, and cross-links.

                    Use Markdown `[link text](PageName)` syntax for internal links.""",
                    topic, topic, pageCount,
                    clusterSlug.isEmpty() ? "" : "Use cluster identifier: \"" + clusterSlug + "\". ",
                    today,
                    clusterSlug.isEmpty() ? "a kebab-case slug derived from the topic" : "\"" + clusterSlug + "\"" );

            return new McpSchema.GetPromptResult(
                    "Guide for creating an article cluster with hub and sub-articles",
                    List.of( new McpSchema.PromptMessage(
                            McpSchema.Role.USER,
                            new McpSchema.TextContent( guide ) ) ) );
        } );
    }

    private static McpServerFeatures.SyncPromptSpecification extendCluster() {
        final McpSchema.Prompt prompt = new McpSchema.Prompt(
                "extend-cluster",
                "Guide adding a new article to an existing cluster",
                List.of(
                        new McpSchema.PromptArgument( "clusterSlug", "Cluster identifier to extend", true ),
                        new McpSchema.PromptArgument( "newPageName", "CamelCase name for the new article", true )
                )
        );

        return new McpServerFeatures.SyncPromptSpecification( prompt, ( exchange, request ) -> {
            final Map< String, Object > args = request.arguments() != null ? request.arguments() : Map.of();
            final String clusterSlug = args.getOrDefault( "clusterSlug", "my-cluster" ).toString();
            final String newPageName = args.getOrDefault( "newPageName", "NewArticle" ).toString();

            final String guide = String.format( """
                    You are adding the article "%s" to the existing cluster "%s".

                    Follow these steps:

                    1. **Find cluster members**: Use `query_metadata` with field="cluster" and value="%s" \
                    to find all existing pages in this cluster.

                    2. **Read the hub**: Identify the hub page (type="hub") and use `read_page` to understand \
                    the cluster structure, naming conventions, and content style.

                    3. **Create the new article**: Use `write_page` for "%s" with:
                       - `content`: the article body in Markdown
                       - `metadata`: match the cluster's metadata schema:
                         - `type`: "article"
                         - `tags`: consistent with existing cluster tags
                         - `date`: %s
                         - `related`: include hub page and relevant sibling articles
                         - `cluster`: "%s"
                         - `status`: "active"
                         - `summary`: one-line description
                       - `author`: set to a descriptive name
                       - `changeNote`: "Initial creation"

                    4. **Update the hub**: Use `batch_patch_pages` to add a link to "%s" in the hub page's \
                    article listing section.

                    5. **Update cross-references**: Use `batch_update_metadata` to add "%s" to the `related` \
                    lists of the hub page and relevant sibling articles.

                    6. **Verify**: Use `verify_pages` on the new article, the hub, and any updated siblings \
                    to confirm:
                       - No broken links
                       - Backlinks are correct
                       - Metadata is complete

                    7. **Verify SEO**: Use `preview_structured_data` on the new article to verify \
                    BreadcrumbList, isPartOf, and cluster feed inclusion.

                    Use Markdown `[link text](PageName)` syntax for internal links.""",
                    newPageName, clusterSlug, clusterSlug, newPageName,
                    java.time.LocalDate.now().toString(), clusterSlug,
                    newPageName, newPageName );

            return new McpSchema.GetPromptResult(
                    "Guide for adding an article to an existing cluster",
                    List.of( new McpSchema.PromptMessage(
                            McpSchema.Role.USER,
                            new McpSchema.TextContent( guide ) ) ) );
        } );
    }

    private static McpServerFeatures.SyncPromptSpecification wikiHealthCheck() {
        final McpSchema.Prompt prompt = new McpSchema.Prompt(
                "wiki-health-check",
                "Guide comprehensive wiki health assessment",
                List.of()
        );

        return new McpServerFeatures.SyncPromptSpecification( prompt, ( exchange, request ) -> {
            final String guide = """
                    You are performing a comprehensive health check of the wiki.

                    Follow these steps:

                    1. **Get an overview**: Use `get_wiki_stats` to get total pages, broken link count, orphaned page count, and recent activity.

                    2. **Review broken links**: Use `get_broken_links` to find all references to non-existent pages. For each broken link, note which pages reference it and consider:
                       - Should the missing page be created?
                       - Is the link a typo that should be corrected?
                       - Has the target page been renamed without updating references?

                    3. **Find orphaned pages**: Use `get_orphaned_pages` to find pages with no incoming links. For each orphan, consider:
                       - Should other pages link to it?
                       - Is it outdated and should be deleted?
                       - Does it need better metadata for discoverability?

                    4. **Check metadata quality**: Use `list_metadata_values` to review what metadata fields and values are in use. Look for:
                       - Inconsistent tag naming (e.g. "security" vs "Security")
                       - Missing type classifications
                       - Pages without tags or summaries

                    5. **Check SEO quality**: Use `verify_pages` with `checks=["seo_readiness"]` on a sample \
                    of pages to assess SEO quality across the wiki. Look for missing summaries, tags, and dates.

                    6. **Review recent activity**: Use `recent_changes` to check recent edits for any issues.

                    7. **Report findings**: Summarize the wiki health status with:
                       - Overall statistics
                       - Critical issues (broken links, orphaned important pages)
                       - Recommendations for improvement
                       - Quick wins that can be fixed immediately""";

            return new McpSchema.GetPromptResult(
                    "Guide for comprehensive wiki health assessment",
                    List.of( new McpSchema.PromptMessage(
                            McpSchema.Role.USER,
                            new McpSchema.TextContent( guide ) ) ) );
        } );
    }

    private static McpServerFeatures.SyncPromptSpecification seoAudit() {
        final McpSchema.Prompt prompt = new McpSchema.Prompt(
                "seo-audit",
                "Guide SEO readiness assessment across the wiki",
                List.of(
                        new McpSchema.PromptArgument( "cluster", "Optional cluster to scope the audit (omit for wiki-wide)", false )
                )
        );

        return new McpServerFeatures.SyncPromptSpecification( prompt, ( exchange, request ) -> {
            final Map< String, Object > args = request.arguments() != null ? request.arguments() : Map.of();
            final String cluster = args.getOrDefault( "cluster", "" ).toString();

            final String guide = String.format( """
                    You are performing an SEO readiness audit%s.

                    Follow these steps:

                    1. **Find pages in scope**: %s

                    2. **Run SEO checks**: Use `verify_pages` with `checks=["seo_readiness"]` on all pages in scope. \
                    Review the seoWarnings for each page and the seoIssues summary.

                    3. **Preview impact**: For pages with warnings, use `preview_structured_data` to see the \
                    exact meta tags, JSON-LD, feed entries, and News Sitemap eligibility they produce. \
                    This shows the real-world impact of the issues.

                    4. **Fix issues**: Use `update_metadata` or `batch_update_metadata` to fix metadata problems:
                       - Add or improve summaries (aim for 50-160 characters)
                       - Add tags to enable News Sitemap inclusion
                       - Add date fields for JSON-LD datePublished
                       - Fix hub pages: ensure related lists reference existing pages
                       - Set type on clustered pages that lack it

                    5. **Re-verify**: Run `verify_pages` with `checks=["seo_readiness"]` again to confirm \
                    all seoIssues are resolved.

                    6. **Report**: Summarize findings:
                       - Pages with good SEO (no warnings)
                       - Pages that needed attention and what was fixed
                       - News-eligible page count (pages with tags modified recently)
                       - Any remaining issues that need manual attention""",
                    cluster.isEmpty() ? " across the entire wiki" : " for cluster \"" + cluster + "\"",
                    cluster.isEmpty()
                            ? "Use `list_pages` to get all pages, or `query_metadata` to find pages by type."
                            : "Use `query_metadata` with field=\"cluster\" and value=\"" + cluster +
                              "\" to find all pages in this cluster." );

            return new McpSchema.GetPromptResult(
                    "Guide for SEO readiness assessment",
                    List.of( new McpSchema.PromptMessage(
                            McpSchema.Role.USER,
                            new McpSchema.TextContent( guide ) ) ) );
        } );
    }
}
