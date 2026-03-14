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
package org.apache.wiki.mcp.prompts;

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
        return List.of( createArticle(), summarizeTopic(), auditLinks() );
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
                         - `summary: one-line description`
                         - `related: [RelatedPageName]` (link to pages found in step 1)
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
}
