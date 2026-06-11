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
package com.wikantik.markdown.extensions.math;

import com.wikantik.api.content.ContentValidationException;
import com.wikantik.api.content.ContentViolation;
import com.wikantik.api.content.ContentWarningSink;
import com.wikantik.api.content.Location;
import com.wikantik.api.core.Context;
import com.wikantik.api.exceptions.FilterException;
import com.wikantik.api.filters.PageFilter;
import com.wikantik.api.frontmatter.FrontmatterParseException;
import com.wikantik.api.frontmatter.FrontmatterParser;
import com.wikantik.api.frontmatter.schema.Severity;
import com.wikantik.api.managers.PageManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Save-time guard that validates LaTeX math in the page body. Runs at priority {@code -1007}
 * (after {@code SchemaValidationPageFilter} at {@code -1006}, so frontmatter/YAML errors take
 * precedence). Gated by {@code wikantik.math.enforcement.enabled} (default {@code true}).
 *
 * <ul>
 *   <li>ERROR violations → {@link ContentValidationException} (the page is not written; HTTP 422 / MCP refusal).</li>
 *   <li>WARNING violations → stashed on {@link ContentWarningSink} for the REST/MCP layer to surface on success.</li>
 * </ul>
 *
 * <p>Violation {@link Location}s are body-relative (after the closing {@code ---} of the YAML
 * frontmatter). The {@code MathStructureValidator} already returns body-relative ranges; syntax
 * violations from {@code LatexSyntaxLinter} are span-local so they are anchored at the
 * containing span's body-relative range.</p>
 */
public class MathValidationPageFilter implements PageFilter {

    private static final Logger LOG = LogManager.getLogger( MathValidationPageFilter.class );

    /** Master flag; default {@code true}. */
    public static final String PROP_ENABLED = "wikantik.math.enforcement.enabled";

    private static final int EXCERPT_WINDOW = 100;

    private final boolean enabled;
    private final MathStructureValidator structureValidator = new MathStructureValidator();
    private final MathSpanExtractor extractor = new MathSpanExtractor();
    private final LatexSyntaxLinter linter = new LatexSyntaxLinter();

    /** Test-friendly constructor. */
    public MathValidationPageFilter( final boolean enabled ) {
        this.enabled = enabled;
        LOG.info( "MathValidationPageFilter: enforcement {}", enabled ? "enabled" : "disabled" );
    }

    /** Production constructor — reads {@link #PROP_ENABLED} from {@code props}. */
    public MathValidationPageFilter( final Properties props, final PageManager pageManager ) {
        this( Boolean.parseBoolean( props.getProperty( PROP_ENABLED, "true" ) ) );
    }

    @Override
    public String preSave( final Context context, final String content ) throws FilterException {
        if ( !enabled || content == null || content.isEmpty() ) {
            return content;
        }

        // Extract the body (strip frontmatter). If the YAML is malformed the schema filter at
        // -1006 already rejected the save — just pass through so we don't double-fault.
        final String body;
        try {
            body = FrontmatterParser.parseStrict( content ).body();
        } catch ( final FrontmatterParseException e ) {
            return content;
        }

        if ( body == null || body.isEmpty() ) {
            return content;
        }

        // Normalize line endings before validating. The save pipeline may persist CRLF, but the
        // editor (CodeMirror) and every validator reason in LF. A stray '\r' before a delimiter or
        // after a script (e.g. "x^\r") otherwise defeats rules like emptyScript and leaks a CR into
        // excerpts. Offsets stay LF-based, matching the editor's body coordinates.
        final String mathBody = body.indexOf( '\r' ) >= 0
                ? body.replace( "\r\n", "\n" ).replace( '\r', '\n' )
                : body;

        final List< ContentViolation > all = new ArrayList<>();

        // Structure violations — ranges are already body-relative.
        for ( final MathViolation v : structureValidator.validate( mathBody ) ) {
            all.add( toContentViolation( mathBody, v ) );
        }

        // Syntax violations — linter ranges are span-local; anchor them at the span range.
        for ( final MathSpan span : extractor.extract( mathBody ) ) {
            for ( final MathViolation v : linter.lint( span.content() ) ) {
                all.add( toContentViolationAnchored( mathBody, v, span.range() ) );
            }
        }

        final List< ContentViolation > errors =
                all.stream().filter( cv -> cv.severity() == Severity.ERROR ).toList();
        final List< ContentViolation > warnings =
                all.stream().filter( cv -> cv.severity() == Severity.WARNING ).toList();

        LOG.debug( "MathValidationPageFilter: {} error(s), {} warning(s)", errors.size(), warnings.size() );

        if ( !errors.isEmpty() ) {
            throw new ContentValidationException( errors );
        }
        if ( !warnings.isEmpty() ) {
            ContentWarningSink.put( warnings );
        }
        return content;
    }

    // -------------------------------------------------------------------------
    // Violation mapping helpers
    // -------------------------------------------------------------------------

    /** Map a body-relative {@code MathViolation} to a {@code ContentViolation}. */
    private static ContentViolation toContentViolation( final String body, final MathViolation v ) {
        return new ContentViolation( "math", v.severity(), v.code(), v.message(),
                buildLocation( body, v.range() ) );
    }

    /**
     * Map a span-local syntax {@code MathViolation} to a body-relative {@code ContentViolation},
     * anchoring the location at the containing span's body-relative range.
     */
    private static ContentViolation toContentViolationAnchored( final String body,
                                                                 final MathViolation v,
                                                                 final MathSourceRange spanRange ) {
        return new ContentViolation( "math", v.severity(), v.code(), v.message(),
                buildLocation( body, spanRange ) );
    }

    /**
     * Build a body-relative {@link Location} from a {@link MathSourceRange} (which already carries
     * line/column/offset data in body coordinates).
     *
     * <p>Computes:
     * <ul>
     *   <li>{@code excerpt} — the line in {@code body} that contains {@code range.startOffset()},
     *       trimmed to at most {@value #EXCERPT_WINDOW} chars with leading/trailing {@code …}.</li>
     *   <li>{@code caret} — spaces aligned to the column of the span start within the excerpt,
     *       followed by {@code ^^^} (three carets) to mark the position; usable as a
     *       compiler-style pointer in plain text.</li>
     * </ul>
     */
    static Location buildLocation( final String body, final MathSourceRange range ) {
        final int start = Math.max( 0, Math.min( range.startOffset(), body.length() ) );

        // Find the line that contains `start`.
        int lineStart = start;
        while ( lineStart > 0 && body.charAt( lineStart - 1 ) != '\n' ) {
            lineStart--;
        }
        int lineEnd = start;
        while ( lineEnd < body.length() && body.charAt( lineEnd ) != '\n' ) {
            lineEnd++;
        }
        final String fullLine = body.substring( lineStart, lineEnd );

        // Column of the span start within the full line (0-based for excerpt math).
        final int colInLine = start - lineStart;

        // Compute excerpt window: try to keep `colInLine` visible.
        final String excerpt;
        final int excerptOffset;   // how many chars were cut from the left
        if ( fullLine.length() <= EXCERPT_WINDOW ) {
            excerpt = fullLine;
            excerptOffset = 0;
        } else {
            // Centre the window on colInLine.
            int winStart = Math.max( 0, colInLine - EXCERPT_WINDOW / 2 );
            int winEnd = Math.min( fullLine.length(), winStart + EXCERPT_WINDOW );
            // Adjust winStart if winEnd hit the boundary.
            winStart = Math.max( 0, winEnd - EXCERPT_WINDOW );
            final String raw = fullLine.substring( winStart, winEnd );
            final boolean leadEllipsis = winStart > 0;
            final boolean trailEllipsis = winEnd < fullLine.length();
            excerpt = ( leadEllipsis ? "…" : "" ) + raw + ( trailEllipsis ? "…" : "" );
            excerptOffset = winStart - ( leadEllipsis ? 1 : 0 );
        }

        // Caret: spaces up to the span-start column in the excerpt, then ^^^.
        final int caretPos = Math.max( 0, colInLine - excerptOffset );
        final String caret = " ".repeat( caretPos ) + "^^^";

        return new Location(
                range.line(),
                range.column(),
                range.endLine(),
                range.endColumn(),
                range.startOffset(),
                range.endOffset(),
                excerpt,
                caret );
    }
}
