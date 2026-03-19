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
package com.wikantik.pages;

import com.wikantik.api.core.Context;
import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Page;
import com.wikantik.api.exceptions.WikiException;
import com.wikantik.api.providers.PageProvider;
import com.wikantik.api.spi.Wiki;
import com.wikantik.frontmatter.FrontmatterParser;
import com.wikantik.frontmatter.FrontmatterWriter;
import com.wikantik.frontmatter.ParsedPage;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Centralizes the page save workflow: optimistic locking, metadata merging,
 * frontmatter serialization, and page attribute setup.
 */
public class PageSaveHelper {

    private final Engine engine;

    public PageSaveHelper( final Engine engine ) {
        this.engine = engine;
    }

    /**
     * Saves page text with the given options.  Performs optimistic locking checks,
     * optional metadata/frontmatter handling, and sets page attributes before delegating
     * to {@link PageManager#saveText(Context, String)}.
     *
     * @param pageName the wiki page name
     * @param text     the page body (without frontmatter when metadata is provided separately)
     * @param options  save configuration
     * @return the saved Page
     * @throws VersionConflictException if optimistic locking fails
     * @throws WikiException            on any other save failure
     */
    public Page saveText( final String pageName, final String text, final SaveOptions options ) throws WikiException {
        final PageManager pm = engine.getManager( PageManager.class );

        // Optimistic locking: version check
        if( options.expectedVersion() > 0 ) {
            final Page current = pm.getPage( pageName );
            if( current != null && Math.max( current.getVersion(), 1 ) != options.expectedVersion() ) {
                throw new VersionConflictException( pageName, Math.max( current.getVersion(), 1 ),
                        options.expectedVersion(), false );
            }
        }

        // Optimistic locking: content hash check
        if( options.expectedContentHash() != null ) {
            final String currentText = pm.getPureText( pageName, PageProvider.LATEST_VERSION );
            final String currentHash = computeContentHash( currentText != null ? currentText : "" );
            if( !currentHash.equals( options.expectedContentHash() ) ) {
                throw new VersionConflictException( pageName, -1, -1, true );
            }
        }

        // Build effective text with frontmatter
        String effectiveText = text;
        if( options.metadata() != null ) {
            final Map< String, Object > effectiveMetadata;
            if( options.replaceMetadata() ) {
                effectiveMetadata = options.metadata();
            } else {
                effectiveMetadata = mergeMetadata( pm, pageName, options.metadata() );
            }
            effectiveText = FrontmatterWriter.write( effectiveMetadata, text );
        }

        // Create page, set attributes, create context, save
        final Page page = Wiki.contents().page( engine, pageName );
        if( options.author() != null ) {
            page.setAuthor( options.author() );
        }
        if( options.markupSyntax() != null ) {
            page.setAttribute( Page.MARKUP_SYNTAX, options.markupSyntax() );
        }
        if( options.changeNote() != null ) {
            page.setAttribute( Page.CHANGENOTE, options.changeNote() );
        }
        final Context context = Wiki.context().create( engine, page );
        pm.saveText( context, effectiveText );

        return pm.getPage( pageName );
    }

    /**
     * Merges caller-supplied metadata with existing page frontmatter.
     * Caller fields take precedence over existing fields.
     */
    public Map< String, Object > mergeMetadata( final PageManager pageManager,
                                                  final String pageName,
                                                  final Map< String, Object > callerMetadata ) {
        final String existingText = pageManager.getPureText( pageName, PageProvider.LATEST_VERSION );
        if( existingText == null || existingText.isEmpty() ) {
            return callerMetadata;
        }

        final ParsedPage parsed = FrontmatterParser.parse( existingText );
        if( parsed.metadata().isEmpty() ) {
            return callerMetadata;
        }

        final Map< String, Object > merged = new LinkedHashMap<>( parsed.metadata() );
        merged.putAll( callerMetadata );
        return merged;
    }

    /**
     * Computes a SHA-256 hex digest of the given content string.
     */
    public static String computeContentHash( final String content ) {
        try {
            final MessageDigest digest = MessageDigest.getInstance( "SHA-256" );
            final byte[] hash = digest.digest( content.getBytes( StandardCharsets.UTF_8 ) );
            return HexFormat.of().formatHex( hash );
        } catch( final NoSuchAlgorithmException e ) {
            throw new RuntimeException( "SHA-256 not available", e );
        }
    }
}
