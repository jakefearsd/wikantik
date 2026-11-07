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
package com.wikantik.collab.adapter;

import com.wikantik.api.core.Page;
import com.wikantik.api.exceptions.WikiException;
import com.wikantik.api.frontmatter.FrontmatterParser;
import com.wikantik.api.frontmatter.ParsedPage;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.pages.PageSaveHelper;
import com.wikantik.api.pages.SaveOptions;
import com.wikantik.api.pages.SaveOutcome;
import com.wikantik.api.providers.PageProvider;
import com.wikantik.collab.CollabPageWriter;
import com.wikantik.collab.model.PageState;
import com.wikantik.collab.model.PublishOutcome;
import com.wikantik.collab.model.PublishRequest;
import com.wikantik.collab.text.CollabText;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Objects;
import java.util.Optional;

/**
 * {@link CollabPageWriter} over {@link PageSaveHelper#saveTracked} (the guarded, atomic save of A2) and the
 * {@link PageManager}. Bodies and metadata cross this port split (frontmatter parsed off the stored text) and
 * LF-normalised (A9); the content hash is {@link CollabText#contentHash} of the LF body, the basis the session uses.
 *
 * <p>A new-page publish ({@code expectedVersion} 0) sets {@code expectAbsent}, so "the page must not exist" is
 * checked under the page's save lock together with the write.</p>
 */
public final class PageSaveHelperPageWriter implements CollabPageWriter {

    private static final Logger LOG = LogManager.getLogger( PageSaveHelperPageWriter.class );

    private final PageManager pages;
    private final PageSaveHelper helper;

    public PageSaveHelperPageWriter( final PageManager pages, final PageSaveHelper helper ) {
        this.pages = Objects.requireNonNull( pages, "pages" );
        this.helper = Objects.requireNonNull( helper, "helper" );
    }

    @Override
    public Optional< PageState > latest( final String pageName ) {
        final Page page = pages.getPage( pageName );
        if( page == null ) {
            return Optional.empty();
        }
        return read( pageName, PageProvider.LATEST_VERSION, page );
    }

    @Override
    public Optional< PageState > version( final String pageName, final int version ) {
        final Page page = pages.getPage( pageName, version );
        if( page == null ) {
            return Optional.empty();
        }
        return read( pageName, version, page );
    }

    private Optional< PageState > read( final String pageName, final int version, final Page page ) {
        final String text = pages.getPureText( pageName, version );
        if( text == null ) {
            LOG.warn( "Page '{}' version {} exists but its text could not be read", pageName, version );
            return Optional.empty();
        }
        final ParsedPage parsed = FrontmatterParser.parse( text );
        final String body = lf( parsed.body() );
        return Optional.of( new PageState( Math.max( page.getVersion(), 1 ), body, parsed.metadata(),
                CollabText.contentHash( body ), page.getAuthor() ) );
    }

    @Override
    public PublishOutcome publish( final PublishRequest request ) throws WikiException {
        final String name = request.pageName();
        final SaveOptions options = SaveOptions.builder()
                .author( request.authorKey() )
                .changeNote( request.changeNote() )
                .expectedVersion( request.expectedVersion() )
                .expectAbsent( request.expectedVersion() == 0 )
                .metadata( request.meta() )
                .replaceMetadata( true )
                .build();
        final SaveOutcome out = helper.saveTracked( name, request.body(), options );
        final int version = out.page() == null ? 0 : Math.max( out.version(), 1 );
        final int readVersion = out.version() > 0 ? out.version() : PageProvider.LATEST_VERSION;
        final String text = pages.getPureText( name, readVersion );
        if( text == null ) {
            LOG.warn( "Page '{}' version {} was saved but its text could not be read back; reporting an empty body",
                    name, readVersion );
        }
        final ParsedPage saved = FrontmatterParser.parse( text == null ? "" : text );
        return new PublishOutcome( version, lf( saved.body() ), saved.metadata(), out.unchanged() );
    }

    private static String lf( final String text ) {
        return text.replace( "\r\n", "\n" ).replace( '\r', '\n' );
    }
}
