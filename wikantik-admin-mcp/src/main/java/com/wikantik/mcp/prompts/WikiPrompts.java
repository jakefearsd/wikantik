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
 * structured interactions with the wiki using the export/import workflow.
 */
public final class WikiPrompts {

    private WikiPrompts() {
    }

    public static List< McpServerFeatures.SyncPromptSpecification > all() {
        return List.of( createArticle(), summarizeTopic(), auditLinks(), renamePage(),
                wikiHealthCheck(), publishCluster(), extendCluster(), seoAudit() );
    }

    private static McpServerFeatures.SyncPromptSpecification createArticle() {
        final McpSchema.Prompt prompt = McpSchema.Prompt.builder( "create-article" )
                .description( "Guide structured article creation with proper metadata" )
                .arguments( List.of(
                        McpSchema.PromptArgument.builder( "topic" ).description( "The topic or title for the new article" ).required( true ).build(),
                        McpSchema.PromptArgument.builder( "type" ).description( "Article type (e.g. report, reference, note, concept)" ).required( false ).build()
                ) )
                .build();

        return new McpServerFeatures.SyncPromptSpecification( prompt, ( exchange, request ) -> {
            final Map< String, Object > args = request.arguments() != null ? request.arguments() : Map.of();
            final String topic = args.getOrDefault( "topic", "NewArticle" ).toString();
            final String type = args.getOrDefault( "type", "reference" ).toString();

            final String guide = String.format( """
                    You are creating a new wiki article about "%s".

                    Follow these steps:

                    1. **Check for existing content**: On `/knowledge-mcp`, use `list_pages` with a relevant prefix, and `retrieve_context` \
                    for "%s" to find related pages.

                    2. **Choose a CamelCase page name**: Page names must be CamelCase with no spaces (e.g. SecurityPolicy).

                    3. **Read related pages**: Use `read_page` (admin) or `read_pages` (`/knowledge-mcp`) on any related pages \
                    so you can add cross-references.

                    4. **Draft the article**: Draft the page text (e.g. for `PageName`) \
                    with YAML frontmatter:
                       ```
                       ---
                       type: %s
                       tags: [relevant, tags]
                       date: %s
                       summary: one-line description (50-160 chars for SEO)
                       related: [RelatedPageName]
                       status: active
                       ---
                       # Article Title

                       Body content in Markdown.
                       ```

                    5. **Publish**: Use `write_pages` to create the new page (it only creates; it does not overwrite).

                    6. **Cross-reference**: If related pages exist, use `update_page` to add links back to the new article.

                    Use Markdown `[link text](PageName)` syntax for internal links.""",
                    topic, topic, type, java.time.LocalDate.now().toString() );

            return McpSchema.GetPromptResult.builder(
                    List.of( new McpSchema.PromptMessage(
                            McpSchema.Role.USER,
                            McpSchema.TextContent.builder( guide ).build() ) ) )
                    .description( "Guide for creating a structured wiki article" )
                    .build();
        } );
    }

    private static McpServerFeatures.SyncPromptSpecification summarizeTopic() {
        final McpSchema.Prompt prompt = McpSchema.Prompt.builder( "summarize-topic" )
                .description( "Guide cross-page research and synthesis on a topic" )
                .arguments( List.of(
                        McpSchema.PromptArgument.builder( "topic" ).description( "The topic to research and summarize" ).required( true ).build()
                ) )
                .build();

        return new McpServerFeatures.SyncPromptSpecification( prompt, ( exchange, request ) -> {
            final Map< String, Object > args = request.arguments() != null ? request.arguments() : Map.of();
            final String topic = args.getOrDefault( "topic", "the topic" ).toString();

            final String guide = String.format( """
                    You are researching "%s" across the wiki to create a comprehensive summary.

                    Follow these steps:

                    1. **Search broadly**: On `/knowledge-mcp`, use `retrieve_context` or `assemble_bundle` with query "%s" to find all relevant pages.

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
                    then use `write_pages` to publish it.""",
                    topic, topic );

            return McpSchema.GetPromptResult.builder(
                    List.of( new McpSchema.PromptMessage(
                            McpSchema.Role.USER,
                            McpSchema.TextContent.builder( guide ).build() ) ) )
                    .description( "Guide for researching and summarizing a topic across the wiki" )
                    .build();
        } );
    }

    private static McpServerFeatures.SyncPromptSpecification auditLinks() {
        final McpSchema.Prompt prompt = McpSchema.Prompt.builder( "audit-links" )
                .description( "Guide checking page link integrity across the wiki" )
                .arguments( List.of(
                        McpSchema.PromptArgument.builder( "pageName" ).description( "The page to audit links for (or 'all' for wiki-wide)" ).required( false ).build()
                ) )
                .build();

        return new McpServerFeatures.SyncPromptSpecification( prompt, ( exchange, request ) -> {
            final Map< String, Object > args = request.arguments() != null ? request.arguments() : Map.of();
            final String pageName = args.getOrDefault( "pageName", "all" ).toString();

            final String guide;
            if ( "all".equals( pageName ) ) {
                guide = """
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
            } else {
                guide = String.format( """
                        You are auditing link integrity for the page "%s".

                        Follow these steps:

                        1. **Read the page**: Use `read_page` for "%s" to get its content.

                        2. **Check links**: Use `get_outbound_links` and `get_backlinks` for "%s".

                        3. **Check metadata links**: Look at the `related` field in the metadata. \
                        Verify those pages exist too.

                        4. **Fix issues**: If fixes are needed, use `read_page` on "%s" and related pages, \
                        edit the text, then `update_page` to apply changes.

                        5. **Report**: List broken links, suggest fixes, and note any related pages that should link here but don't.""",
                        pageName, pageName, pageName, pageName );
            }

            return McpSchema.GetPromptResult.builder(
                    List.of( new McpSchema.PromptMessage(
                            McpSchema.Role.USER,
                            McpSchema.TextContent.builder( guide ).build() ) ) )
                    .description( "Guide for auditing link integrity" )
                    .build();
        } );
    }

    private static McpServerFeatures.SyncPromptSpecification renamePage() {
        final McpSchema.Prompt prompt = McpSchema.Prompt.builder( "rename-page" )
                .description( "Guide safe page rename workflow with link updates" )
                .arguments( List.of(
                        McpSchema.PromptArgument.builder( "oldName" ).description( "Current page name to rename" ).required( true ).build(),
                        McpSchema.PromptArgument.builder( "newName" ).description( "New page name (CamelCase)" ).required( true ).build()
                ) )
                .build();

        return new McpServerFeatures.SyncPromptSpecification( prompt, ( exchange, request ) -> {
            final Map< String, Object > args = request.arguments() != null ? request.arguments() : Map.of();
            final String oldName = args.getOrDefault( "oldName", "OldPageName" ).toString();
            final String newName = args.getOrDefault( "newName", "NewPageName" ).toString();

            final String guide = String.format( """
                    You are renaming the wiki page "%s" to "%s".

                    Follow these steps for a safe rename:

                    1. **Verify the source page exists**: Use `read_page` for "%s" to confirm it exists.

                    2. **Check the target name is available**: Use `read_page` for "%s" to confirm no page exists with that name.

                    3. **Review incoming links**: Use `get_backlinks` for "%s" to see which pages link to it. \
                    These will be updated automatically if updateLinks=true.

                    4. **Perform the rename**: Use `rename_page` with:
                       - `oldName`: "%s"
                       - `newName`: "%s"
                       - `updateLinks`: true
                       - `confirm`: true

                    5. **Verify**: Use `read_page` for "%s" to confirm the page exists with correct content. \
                    Use `get_backlinks` for "%s" to verify referencing pages were updated.

                    Note: System pages cannot be renamed. The rename operation moves content, attachments, and version history.""",
                    oldName, newName, oldName, newName, oldName, oldName, newName, newName, newName );

            return McpSchema.GetPromptResult.builder(
                    List.of( new McpSchema.PromptMessage(
                            McpSchema.Role.USER,
                            McpSchema.TextContent.builder( guide ).build() ) ) )
                    .description( "Guide for safely renaming a wiki page" )
                    .build();
        } );
    }

    private static McpServerFeatures.SyncPromptSpecification publishCluster() {
        final McpSchema.Prompt prompt = McpSchema.Prompt.builder( "publish-cluster" )
                .description( "Guide creation of a complete article cluster with hub and sub-articles" )
                .arguments( List.of(
                        McpSchema.PromptArgument.builder( "topic" ).description( "The topic for the article cluster" ).required( true ).build(),
                        McpSchema.PromptArgument.builder( "pageCount" ).description( "Desired number of sub-articles (default: 3-5)" ).required( false ).build(),
                        McpSchema.PromptArgument.builder( "clusterSlug" ).description( "Kebab-case cluster identifier (auto-generated if omitted)" ).required( false ).build()
                ) )
                .build();

        return new McpServerFeatures.SyncPromptSpecification( prompt, ( exchange, request ) -> {
            final Map< String, Object > args = request.arguments() != null ? request.arguments() : Map.of();
            final String topic = args.getOrDefault( "topic", "NewCluster" ).toString();
            final String pageCount = args.getOrDefault( "pageCount", "3-5" ).toString();
            final String clusterSlug = args.getOrDefault( "clusterSlug", "" ).toString();
            final String today = java.time.LocalDate.now().toString();

            final String guide = String.format( """
                    You are creating a cluster of interlinked wiki articles about "%s".

                    Follow these steps:

                    1. **Survey existing content**: On `/knowledge-mcp`, use `retrieve_context` with query "%s" and `list_metadata_values` \
                    to discover related pages and metadata conventions already in use.

                    2. **Read related pages**: Use `read_page` (admin) or `read_pages` (`/knowledge-mcp`) on any existing \
                    related pages for cross-referencing.

                    3. **Draft the pages**: Draft the text of %s pages:
                       - **Hub page** (e.g. `TopicOverview`):
                         ```
                         ---
                         type: hub
                         tags: [topic-tags]
                         date: %s
                         summary: overview of the cluster topic
                         related: [SubArticle1, SubArticle2]
                         cluster: %s
                         status: active
                         ---
                         ```
                       - **Sub-articles** (e.g. `SubArticle1`):
                         ```
                         ---
                         type: article
                         tags: [topic-tags]
                         date: %s
                         summary: one-line description
                         related: [HubPage, OtherSubArticle]
                         cluster: %s
                         status: active
                         ---
                         ```
                       Link hub → sub-articles and sub-articles → hub using `[text](PageName)`.

                    4. **Review**: Re-read each draft to verify the frontmatter and links look correct.

                    5. **Publish**: Use `write_pages` to create all pages in one call (create-only).

                    6. **Verify**: Use `verify_pages` on all cluster pages to confirm no broken links \
                    and metadata is complete. Use `preview_structured_data` on the hub to verify JSON-LD.""",
                    topic, topic, pageCount, today,
                    clusterSlug.isEmpty() ? "a-kebab-case-slug" : clusterSlug,
                    today,
                    clusterSlug.isEmpty() ? "a-kebab-case-slug" : clusterSlug );

            return McpSchema.GetPromptResult.builder(
                    List.of( new McpSchema.PromptMessage(
                            McpSchema.Role.USER,
                            McpSchema.TextContent.builder( guide ).build() ) ) )
                    .description( "Guide for creating an article cluster with hub and sub-articles" )
                    .build();
        } );
    }

    private static McpServerFeatures.SyncPromptSpecification extendCluster() {
        final McpSchema.Prompt prompt = McpSchema.Prompt.builder( "extend-cluster" )
                .description( "Guide adding a new article to an existing cluster" )
                .arguments( List.of(
                        McpSchema.PromptArgument.builder( "clusterSlug" ).description( "Cluster identifier to extend" ).required( true ).build(),
                        McpSchema.PromptArgument.builder( "newPageName" ).description( "CamelCase name for the new article" ).required( true ).build()
                ) )
                .build();

        return new McpServerFeatures.SyncPromptSpecification( prompt, ( exchange, request ) -> {
            final Map< String, Object > args = request.arguments() != null ? request.arguments() : Map.of();
            final String clusterSlug = args.getOrDefault( "clusterSlug", "my-cluster" ).toString();
            final String newPageName = args.getOrDefault( "newPageName", "NewArticle" ).toString();

            final String guide = String.format( """
                    You are adding the article "%s" to the existing cluster "%s".

                    Follow these steps:

                    1. **Find cluster members**: On `/knowledge-mcp`, use `list_pages_by_filter` with cluster="%s".

                    2. **Read the cluster**: Use `read_pages` (`/knowledge-mcp`) with the cluster page names to get \
                    all existing cluster content.

                    3. **Create the new article**: Draft the page `%s` with matching metadata:
                       ```
                       ---
                       type: article
                       tags: [consistent-with-cluster]
                       date: %s
                       summary: one-line description
                       related: [HubPage, RelevantSiblings]
                       cluster: "%s"
                       status: active
                       ---
                       ```

                    4. **Update existing pages**: Edit the hub page to add a link to "%s". \
                    Update `related` lists in relevant sibling articles.

                    5. **Publish**: Use `write_pages` for the new article and `update_page` for the hub and siblings.

                    6. **Verify**: Use `verify_pages` on the new article and updated pages.""",
                    newPageName, clusterSlug, clusterSlug, newPageName,
                    java.time.LocalDate.now().toString(), clusterSlug, newPageName );

            return McpSchema.GetPromptResult.builder(
                    List.of( new McpSchema.PromptMessage(
                            McpSchema.Role.USER,
                            McpSchema.TextContent.builder( guide ).build() ) ) )
                    .description( "Guide for adding an article to an existing cluster" )
                    .build();
        } );
    }

    private static McpServerFeatures.SyncPromptSpecification wikiHealthCheck() {
        final McpSchema.Prompt prompt = McpSchema.Prompt.builder( "wiki-health-check" )
                .description( "Guide comprehensive wiki health assessment" )
                .arguments( List.of() )
                .build();

        return new McpServerFeatures.SyncPromptSpecification( prompt, ( exchange, request ) -> {
            final String guide = """
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

            return McpSchema.GetPromptResult.builder(
                    List.of( new McpSchema.PromptMessage(
                            McpSchema.Role.USER,
                            McpSchema.TextContent.builder( guide ).build() ) ) )
                    .description( "Guide for comprehensive wiki health assessment" )
                    .build();
        } );
    }

    private static McpServerFeatures.SyncPromptSpecification seoAudit() {
        final McpSchema.Prompt prompt = McpSchema.Prompt.builder( "seo-audit" )
                .description( "Guide SEO readiness assessment across the wiki" )
                .arguments( List.of(
                        McpSchema.PromptArgument.builder( "cluster" ).description( "Optional cluster to scope the audit (omit for wiki-wide)" ).required( false ).build()
                ) )
                .build();

        return new McpServerFeatures.SyncPromptSpecification( prompt, ( exchange, request ) -> {
            final Map< String, Object > args = request.arguments() != null ? request.arguments() : Map.of();
            final String cluster = args.getOrDefault( "cluster", "" ).toString();

            final String guide = String.format( """
                    You are performing an SEO readiness audit%s.

                    Follow these steps:

                    1. **Find pages in scope**: %s

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
                       - Any remaining issues""",
                    cluster.isEmpty() ? " across the entire wiki" : " for cluster \"" + cluster + "\"",
                    cluster.isEmpty()
                            ? "On `/knowledge-mcp`, use `list_pages` to get all pages, or `list_pages_by_filter` to find pages by type."
                            : "On `/knowledge-mcp`, use `list_pages_by_filter` with cluster=\"" + cluster +
                              "\" to find all pages in this cluster." );

            return McpSchema.GetPromptResult.builder(
                    List.of( new McpSchema.PromptMessage(
                            McpSchema.Role.USER,
                            McpSchema.TextContent.builder( guide ).build() ) ) )
                    .description( "Guide for SEO readiness assessment" )
                    .build();
        } );
    }
}
