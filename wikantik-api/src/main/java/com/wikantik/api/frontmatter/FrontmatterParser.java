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
package com.wikantik.api.frontmatter;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.MarkedYAMLException;
import org.yaml.snakeyaml.error.YAMLException;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses YAML frontmatter from wiki page text. Frontmatter is delimited by {@code ---}
 * markers at the very start of the page text. Handles both LF and CRLF line endings
 * (JSPWiki normalizes stored text to CRLF).
 */
public final class FrontmatterParser {

    private static final Logger LOG = LogManager.getLogger( FrontmatterParser.class );

    /** Matches the opening --- followed by a newline (LF or CRLF). */
    private static final Pattern OPENING = Pattern.compile( "\\A---\\r?\\n" );

    /** Matches the closing delimiter: either immediately after opening (empty block) or on its own line. */
    private static final Pattern CLOSING_IMMEDIATE = Pattern.compile( "\\A---\\r?\\n" );
    private static final Pattern CLOSING = Pattern.compile( "\\r?\\n---\\r?\\n" );

    private FrontmatterParser() {
    }

    /** Hardened SnakeYAML loader options — bounds frontmatter input against
     *  YAML-bomb / billion-laughs DoS. Applied to every parser instance. */
    private static LoaderOptions hardenedLoaderOptions() {
        final LoaderOptions opts = new LoaderOptions();
        opts.setMaxAliasesForCollections( 50 );      // anchor/alias expansion fan-out cap
        opts.setNestingDepthLimit( 50 );             // structure nesting cap
        opts.setCodePointLimit( 1024 * 1024 );       // 1 MB per frontmatter block
        opts.setAllowDuplicateKeys( false );         // duplicate keys are caller errors
        return opts;
    }

    /**
     * Hardened SnakeYAML parser, reused per thread. Constructing a {@code Yaml}
     * builds a {@code Resolver} that compiles ~10 regex {@code Pattern}s plus the
     * {@code LoaderOptions} — non-trivial allocation + CPU that, before this, ran
     * on every frontmatter parse and dominated the retrieval path (the context
     * retriever re-parses every candidate page's frontmatter per query). A
     * {@code Yaml} is not thread-safe, but is safe to reuse for sequential
     * {@code load()} calls on a single thread, so a {@link ThreadLocal} removes
     * the per-parse construction without sharing mutable parser state across
     * worker threads. Mirrors the per-thread {@code Collator} pattern used to
     * de-contend {@code PrincipalComparator}.
     */
    private static final ThreadLocal< Yaml > HARDENED_YAML =
        ThreadLocal.withInitial( () -> new Yaml( hardenedLoaderOptions() ) );

    /**
     * Parses a page text that may contain YAML frontmatter.
     *
     * @param text the raw page text (may be null)
     * @return a ParsedPage with metadata map and body text
     */
    public static ParsedPage parse( final String text ) {
        if ( text == null || text.isEmpty() ) {
            return new ParsedPage( Map.of(), text == null ? "" : text );
        }

        final Matcher openMatcher = OPENING.matcher( text );
        if ( !openMatcher.find() ) {
            return new ParsedPage( Map.of(), text );
        }

        final int yamlStart = openMatcher.end(); // position right after "---\n" or "---\r\n"

        // Check for empty frontmatter: closing --- immediately follows opening ---
        final Matcher immediateMatcher = CLOSING_IMMEDIATE.matcher( text.substring( yamlStart ) );
        if ( immediateMatcher.find() ) {
            final String body = text.substring( yamlStart + immediateMatcher.end() );
            return parseYaml( "", body );
        }

        final Matcher closeMatcher = CLOSING.matcher( text );
        if ( !closeMatcher.find( yamlStart ) ) {
            // No closing delimiter found — check if text ends with \n--- (no trailing newline)
            if ( text.endsWith( "\n---" ) || text.endsWith( "\r\n---" ) ) {
                final int lastDelim = text.lastIndexOf( "---" );
                // Walk back past optional \r
                int bodyEnd = lastDelim;
                if ( bodyEnd > 0 && text.charAt( bodyEnd - 1 ) == '\n' ) {
                    bodyEnd--;
                }
                if ( bodyEnd > 0 && text.charAt( bodyEnd - 1 ) == '\r' ) {
                    bodyEnd--;
                }
                if ( bodyEnd >= yamlStart ) {
                    return parseYaml( text.substring( yamlStart, bodyEnd ), "" );
                }
            }
            return new ParsedPage( Map.of(), text );
        }

        final String yamlBlock = text.substring( yamlStart, closeMatcher.start() );
        final String body = text.substring( closeMatcher.end() );

        return parseYaml( yamlBlock, body );
    }

    @SuppressWarnings( "unchecked" )
    private static ParsedPage parseYaml( final String yamlBlock, final String body ) {
        if ( yamlBlock.isBlank() ) {
            return new ParsedPage( Map.of(), body );
        }

        try {
            final Yaml yaml = HARDENED_YAML.get();
            final Object parsed = yaml.load( yamlBlock );
            if ( parsed instanceof Map ) {
                return new ParsedPage( Map.copyOf( ( Map< String, Object > ) parsed ), body );
            }
            return new ParsedPage( Map.of(), body );
        } catch ( final MarkedYAMLException e ) {
            final int line = e.getProblemMark() != null ? e.getProblemMark().getLine() + 1 : -1;
            final int column = e.getProblemMark() != null ? e.getProblemMark().getColumn() + 1 : -1;
            // Including line/column in the relaxed log so operators can locate the
            // offending page without enabling DEBUG. First-line excerpt of the YAML
            // block is the cheapest proxy for "which page" — typically the page's
            // title field — when callers don't pass a context label.
            final String firstLine = yamlBlock.lines().findFirst().orElse( "" ).strip();
            LOG.warn( "Failed to parse YAML frontmatter at line {} col {} (near \"{}\"): {}",
                    line, column, firstLine, e.getMessage() );
            return new ParsedPage( Map.of(), body );
        } catch ( final Exception e ) {
            LOG.warn( "Failed to parse YAML frontmatter: {}", e.getMessage() );
            return new ParsedPage( Map.of(), body );
        }
    }

    /**
     * Strict variant of {@link #parse(String)} that throws {@link FrontmatterParseException}
     * when a {@code ---}-delimited block exists but the YAML inside fails to parse. Used by
     * MCP write tools and {@code FrontmatterValidationPageFilter} so authoring agents see
     * the YAML error (e.g. unquoted colon in a {@code title:}) rather than silently saving
     * a page with empty metadata.
     *
     * <p>Pages without a frontmatter block, or with empty {@code ---\n---\n} blocks, return
     * a {@link ParsedPage} with empty metadata — those are valid states, not parse errors.</p>
     *
     * @throws FrontmatterParseException when the YAML block is malformed (carries SnakeYAML
     *         message + best-effort line/column).
     */
    public static ParsedPage parseStrict( final String text ) throws FrontmatterParseException {
        if ( text == null || text.isEmpty() ) {
            return new ParsedPage( Map.of(), text == null ? "" : text );
        }

        final Matcher openMatcher = OPENING.matcher( text );
        if ( !openMatcher.find() ) {
            return new ParsedPage( Map.of(), text );
        }

        final int yamlStart = openMatcher.end();

        // Empty frontmatter block — valid, no parsing to do.
        final Matcher immediateMatcher = CLOSING_IMMEDIATE.matcher( text.substring( yamlStart ) );
        if ( immediateMatcher.find() ) {
            return new ParsedPage( Map.of(), text.substring( yamlStart + immediateMatcher.end() ) );
        }

        final Matcher closeMatcher = CLOSING.matcher( text );
        final String yamlBlock;
        final String body;
        if ( !closeMatcher.find( yamlStart ) ) {
            // Tolerate text ending with \n--- (no trailing newline) — same as parse().
            if ( text.endsWith( "\n---" ) || text.endsWith( "\r\n---" ) ) {
                final int lastDelim = text.lastIndexOf( "---" );
                int bodyEnd = lastDelim;
                if ( bodyEnd > 0 && text.charAt( bodyEnd - 1 ) == '\n' ) {
                    bodyEnd--;
                }
                if ( bodyEnd > 0 && text.charAt( bodyEnd - 1 ) == '\r' ) {
                    bodyEnd--;
                }
                if ( bodyEnd >= yamlStart ) {
                    yamlBlock = text.substring( yamlStart, bodyEnd );
                    body = "";
                } else {
                    return new ParsedPage( Map.of(), text );
                }
            } else {
                // Opened a block but never closed it. parse() falls back to "no frontmatter";
                // strict parsing flags this so the agent knows the closing --- is missing.
                throw new FrontmatterParseException(
                        "Frontmatter block opened with '---' but no closing '---' found before end of page.",
                        -1, -1 );
            }
        } else {
            yamlBlock = text.substring( yamlStart, closeMatcher.start() );
            body = text.substring( closeMatcher.end() );
        }

        if ( yamlBlock.isBlank() ) {
            return new ParsedPage( Map.of(), body );
        }

        try {
            final Yaml yaml = HARDENED_YAML.get();
            final Object parsed = yaml.load( yamlBlock );
            if ( parsed instanceof Map ) {
                @SuppressWarnings( "unchecked" )
                final Map< String, Object > map = ( Map< String, Object > ) parsed;
                return new ParsedPage( Map.copyOf( map ), body );
            }
            // Non-map at top level (e.g. a bare scalar) — treat as empty metadata, not an error.
            return new ParsedPage( Map.of(), body );
        } catch ( final MarkedYAMLException e ) {
            // SnakeYAML's positioned error — pull line/column for the agent's message.
            final int line = e.getProblemMark() != null ? e.getProblemMark().getLine() + 1 : -1;
            final int column = e.getProblemMark() != null ? e.getProblemMark().getColumn() + 1 : -1;
            throw new FrontmatterParseException( e.getMessage(), line, column, e );
        } catch ( final YAMLException e ) {
            throw new FrontmatterParseException( e.getMessage(), -1, -1, e );
        }
    }
}
