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
package com.wikantik.citation;

import com.wikantik.api.core.Context;
import com.wikantik.api.exceptions.FilterException;
import com.wikantik.api.filters.PageFilter;
import com.wikantik.api.pagegraph.StructuralIndexService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A {@link PageFilter} that rewrites {@code cite://} hrefs in rendered HTML to real
 * wiki anchor links ({@code /wiki/<slug>}) so that citation links are navigable.
 *
 * <p>Matches {@code href="cite://<canonical_id>"} and
 * {@code href="cite://<canonical_id>/<heading%20path>"} in the rendered HTML, then:</p>
 * <ul>
 *   <li>Resolves the canonical_id to a slug via {@link StructuralIndexService#resolveSlugFromCanonicalId}.</li>
 *   <li>Builds a best-effort heading anchor from the last decoded heading segment
 *       (lowercase, spaces → {@code -}, non-alphanumerics stripped).</li>
 *   <li>Adds {@code class="wiki-citation"} to the element.</li>
 *   <li>Drops the {@code title="…"} attribute Flexmark renders from the link title (the verbatim
 *       cited span) — that span quotes the target page and must not leak to readers on hover.</li>
 *   <li>On unknown cid: leaves a non-navigating anchor ({@code href="#"}) with
 *       {@code class="wiki-citation wiki-citation-missing"}; never throws.</li>
 * </ul>
 *
 * <p>Staleness is never surfaced to readers — this filter is purely link rewriting.</p>
 */
public final class CitationLinkRenderingFilter implements PageFilter {

    private static final Logger LOG = LogManager.getLogger( CitationLinkRenderingFilter.class );

    /**
     * Matches {@code href="cite://<cid>"} or {@code href="cite://<cid>/<heading>"}, plus an
     * optional trailing {@code title="…"} attribute. Flexmark renders the markdown link title
     * (the verbatim cited span) into {@code title="…"}; we consume it here so it is DROPPED on
     * rewrite — that span is a quote of the <em>target</em> page's content and must not surface
     * to readers (incl. anonymous) on hover when they may lack view permission on the target.
     * Group 1 = cid, group 2 = heading path (may be null/absent). The title group is intentionally
     * non-capturing and never re-emitted.
     */
    private static final Pattern CITE_HREF = Pattern.compile(
        "href=\"cite://([^\"/]+)(?:/([^\"]*))?\"(?:\\s+title=\"[^\"]*\")?" );

    private final StructuralIndexService structuralIndex;

    public CitationLinkRenderingFilter( final StructuralIndexService structuralIndex ) {
        this.structuralIndex = structuralIndex;
    }

    @Override
    public String postTranslate( final Context context, final String htmlContent )
            throws FilterException {
        if ( htmlContent == null || !htmlContent.contains( "cite://" ) ) {
            return htmlContent;
        }

        final StringBuffer sb = new StringBuffer( htmlContent.length() );
        final Matcher m = CITE_HREF.matcher( htmlContent );
        while ( m.find() ) {
            final String cid         = m.group( 1 );
            final String headingPath = m.group( 2 ); // may be null
            try {
                final Optional< String > slug = structuralIndex.resolveSlugFromCanonicalId( cid );
                final String replacement;
                if ( slug.isPresent() ) {
                    final String anchor = buildAnchor( headingPath );
                    final String href   = "/wiki/" + slug.get() + anchor;
                    replacement = "href=\"" + href + "\" class=\"wiki-citation\"";
                } else {
                    LOG.warn( "cite:// link references unknown canonical_id='{}'; rendering as missing", cid );
                    replacement = "href=\"#\" class=\"wiki-citation wiki-citation-missing\"";
                }
                m.appendReplacement( sb, Matcher.quoteReplacement( replacement ) );
            } catch ( final RuntimeException e ) {
                LOG.warn( "cite:// link rewrite failed for cid='{}': {}; leaving original href",
                    cid, e.getMessage(), e );
                m.appendReplacement( sb, Matcher.quoteReplacement( m.group( 0 ) ) );
            }
        }
        m.appendTail( sb );
        return sb.toString();
    }

    /**
     * Derives a best-effort heading anchor from the last URL-decoded heading path segment.
     * Returns an empty string when no heading path is given.
     */
    private static String buildAnchor( final String headingPath ) {
        if ( headingPath == null || headingPath.isBlank() ) {
            return "";
        }
        final String decoded;
        try {
            decoded = URLDecoder.decode( headingPath, StandardCharsets.UTF_8 );
        } catch ( final RuntimeException e ) {
            LOG.warn( "cite:// heading path decode failed for '{}': {}", headingPath, e.getMessage() );
            return "";
        }
        // Take the last segment (after the last '/') as the anchor label.
        final String lastSegment;
        final int slash = decoded.lastIndexOf( '/' );
        lastSegment = ( slash >= 0 ) ? decoded.substring( slash + 1 ) : decoded;

        // Normalise: lowercase, spaces → '-', strip non-alphanumerics (except '-').
        final String anchor = lastSegment.toLowerCase( Locale.ROOT )
            .replaceAll( "\\s+", "-" )
            .replaceAll( "[^a-z0-9\\-]", "" );
        return anchor.isEmpty() ? "" : "#" + anchor;
    }
}
