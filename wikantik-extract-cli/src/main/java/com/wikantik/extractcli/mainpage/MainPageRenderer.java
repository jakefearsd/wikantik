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
package com.wikantik.extractcli.mainpage;

import com.samskivert.mustache.Mustache;
import com.samskivert.mustache.Template;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders {@link MainPageData} to the Main.md markdown body using the
 * bundled Mustache template at {@code Main.md.mustache}. Output is
 * deterministic: LF line endings, no locale-sensitive ordering, no
 * timestamps.
 */
public final class MainPageRenderer {

    private static final String TEMPLATE_RESOURCE = "/Main.md.mustache";

    private final Template compiled;

    public MainPageRenderer() {
        try ( InputStream in = MainPageRenderer.class.getResourceAsStream( TEMPLATE_RESOURCE ) ) {
            if ( in == null ) {
                throw new IllegalStateException(
                        "Mustache template " + TEMPLATE_RESOURCE + " not found on the classpath" );
            }
            try ( BufferedReader reader = new BufferedReader(
                    new InputStreamReader( in, StandardCharsets.UTF_8 ) ) ) {
                this.compiled = Mustache.compiler()
                        .standardsMode( false )
                        .escapeHTML( false )
                        .compile( reader );
            }
        } catch ( final IOException e ) {
            throw new IllegalStateException( "Could not load template " + TEMPLATE_RESOURCE, e );
        }
    }

    public String render( final MainPageData data ) {
        final String body = compiled.execute( asContext( data ) );
        return normalize( body );
    }

    /**
     * Adapt {@link MainPageData} to a Mustache context. Booleans like
     * {@code hasIntro} guide section visibility because Mustache truthiness
     * over a String checks emptiness in lambda mode but not in the standards
     * mode we set above.
     */
    private static Map< String, Object > asContext( final MainPageData data ) {
        final Map< String, Object > root = new LinkedHashMap<>();
        root.put( "intro", data.intro() );
        root.put( "hasIntro", !data.intro().isBlank() );
        root.put( "footer", data.footer() );
        root.put( "hasFooter", !data.footer().isBlank() );

        final List< Map< String, Object > > sections = new ArrayList<>( data.sections().size() );
        for ( final MainPageData.Section s : data.sections() ) {
            final Map< String, Object > sect = new LinkedHashMap<>();
            sect.put( "label", s.label() );
            sect.put( "cluster", s.cluster() == null ? "" : s.cluster() );
            final List< Map< String, Object > > pages = new ArrayList<>( s.pages().size() );
            for ( final MainPageData.Page p : s.pages() ) {
                final Map< String, Object > pg = new LinkedHashMap<>();
                pg.put( "title", p.title() );
                pg.put( "slug", p.slug() );
                pg.put( "summary", p.summary() );
                pg.put( "hasSummary", !p.summary().isBlank() );
                pages.add( pg );
            }
            sect.put( "pages", pages );
            sections.add( sect );
        }
        root.put( "sections", sections );
        root.put( "hasSections", !sections.isEmpty() );
        return root;
    }

    /**
     * Force a single trailing newline and LF line endings so the regression
     * test can byte-compare against the on-disk file regardless of the
     * platform that wrote it.
     */
    static String normalize( final String body ) {
        String s = body == null ? "" : body.replace( "\r\n", "\n" );
        // Strip a stray leading newline introduced by the template's first
        // {{#hasIntro}} block when the intro is non-empty (Mustache renders the
        // tag's surrounding whitespace verbatim).
        if ( s.startsWith( "\n" ) ) {
            s = s.substring( 1 );
        }
        // Collapse 3+ consecutive newlines down to 2 for cleaner markdown.
        while ( s.contains( "\n\n\n" ) ) {
            s = s.replace( "\n\n\n", "\n\n" );
        }
        if ( !s.endsWith( "\n" ) ) {
            s = s + "\n";
        }
        return s;
    }
}
