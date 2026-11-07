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
import com.wikantik.api.exceptions.ProviderException;
import com.wikantik.api.exceptions.WikiException;
import com.wikantik.api.frontmatter.FrontmatterParser;
import com.wikantik.api.frontmatter.ParsedPage;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.pages.PageSaveHelper;
import com.wikantik.api.pages.SaveGuard;
import com.wikantik.api.pages.SaveOptions;
import com.wikantik.api.pages.SaveOutcome;
import com.wikantik.api.pages.SavedButPostSaveFailedException;
import com.wikantik.api.pages.VersionConflictException;
import com.wikantik.api.providers.PageProvider;
import com.wikantik.collab.CollabPageWriter;
import com.wikantik.collab.PageReadException;
import com.wikantik.collab.PublishReadBackException;
import com.wikantik.collab.model.PageState;
import com.wikantik.collab.model.PublishOutcome;
import com.wikantik.collab.model.PublishRequest;
import com.wikantik.collab.text.CollabText;
import com.wikantik.providers.NoSuchVersionException;
import com.wikantik.providers.RepositoryModifiedException;
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
 * checked under the page's save lock together with the write. A publish on a base version requires the page to exist,
 * under the same lock: a page renamed or deleted away is a {@code page-gone} conflict, not a recreate (ruling Q1; REST
 * PUT and {@link PageSaveHelper} keep creating a missing page).</p>
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
        return read( pageName, PageProvider.LATEST_VERSION );
    }

    @Override
    public Optional< PageState > version( final String pageName, final int version ) {
        return read( pageName, version );
    }

    /**
     * From the page's metadata alone: no text read. The provider's {@link PageProvider#getPageInfoNoMetadata} throws on
     * a provider error, where the page manager's {@code getPageInfo} would also run {@code CachingProvider}'s metadata
     * refresh (a text read and a markup parse) and its {@code pageExists} would answer false. A repository modified
     * under the lookup is asked once more, as the page manager does.
     */
    @Override
    public boolean exists( final String pageName ) {
        try {
            return pageInfoNoMetadata( pageName ) != null;
        } catch( final ProviderException e ) {
            throw new PageReadException( "collab: could not tell whether page '" + pageName + "' exists", e );
        }
    }

    private Page pageInfoNoMetadata( final String pageName ) throws ProviderException {
        final PageProvider provider = pages.getProvider();
        try {
            return provider.getPageInfoNoMetadata( pageName, PageProvider.LATEST_VERSION );
        } catch( final RepositoryModifiedException e ) {
            LOG.debug( "Page '{}' was modified outside the wiki while its existence was checked; asking again: {}",
                    pageName, e.getMessage() );
            return provider.getPageInfoNoMetadata( pageName, PageProvider.LATEST_VERSION );
        }
    }

    /**
     * Present, absent or unknown. Built on {@link PageManager#getPageInfo} and {@link PageManager#getPageText}, which
     * throw: the lenient {@code getPage} answers null and {@code getPureText} answers "" on a provider error, which would
     * read as a missing page or an empty body (a settle merging "" empties the draft's base). The text read is of the
     * version the page info names, also for the latest version: reading "latest" twice would let a save landing between
     * the two reads pair version N with the text of N+1. A specific version that vanished between the two reads
     * ({@link NoSuchVersionException}) is absent; for the latest version it is unknown (another version may now be the
     * latest). Any other provider error, or an existing page whose text reads as null, throws {@link PageReadException}.
     */
    private Optional< PageState > read( final String pageName, final int version ) {
        final Page page;
        final String text;
        final int textVersion;
        try {
            page = pages.getPageInfo( pageName, version );
            if( page == null ) {
                return Optional.empty();
            }
            // Reading the text by the number the info named narrows the torn-read window to providers that keep no
            // version numbers, and to an outside overwrite of the same version (the directory watcher's write, which makes
            // no new version). There the pair can still be (N, text written just after the info read). That heals: the
            // session reconciles by content hash, not by version number (A1). The next settle sees a hash that differs
            // from the base it adopted and merges the difference. No text is lost, and none is merged twice.
            // A provider that keeps no version numbers reports none: its only text is the latest.
            textVersion = version == PageProvider.LATEST_VERSION && page.getVersion() > 0 ? page.getVersion() : version;
            text = pages.getPageText( pageName, textVersion );
        } catch( final NoSuchVersionException e ) {
            if( version == PageProvider.LATEST_VERSION ) {
                throw new PageReadException( "collab: the latest version of page '" + pageName
                        + "' changed while it was read", e );
            }
            LOG.debug( "Page '{}' version {} vanished while it was read: {}", pageName, version, e.getMessage() );
            return Optional.empty();
        } catch( final ProviderException e ) {
            throw new PageReadException( "collab: could not read page '" + pageName + "' version " + version, e );
        }
        if( text == null ) {
            throw new PageReadException( "collab: page '" + pageName + "' version " + textVersion
                    + " exists but its text could not be read" );
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
                .changeNote( request.changeNote() ).markupSyntax( request.markupSyntax() )
                .expectedVersion( request.expectedVersion() )
                .expectAbsent( request.expectedVersion() == 0 )
                .metadata( request.meta() )
                .replaceMetadata( true )
                .build();
        final SaveOutcome out = save( name, request, options );
        final int version = out.page() == null ? 0 : Math.max( out.version(), 1 );
        final int readVersion = out.version() > 0 ? out.version() : PageProvider.LATEST_VERSION;
        final ParsedPage saved = FrontmatterParser.parse( readBack( name, readVersion, version ) );
        return new PublishOutcome( version, lf( saved.body() ), saved.metadata(), out.unchanged() );
    }

    /**
     * The text a publish just saved. A failure throws {@link PublishReadBackException} naming the version the save wrote:
     * the save landed, so the session's failed-publish path claims that version as the publisher's own (E39), also when a
     * save filter rewrote it. An empty body instead would be mapped as the filters' rewrite and wipe the draft.
     */
    private String readBack( final String name, final int readVersion, final int savedVersion ) throws WikiException {
        final String text;
        try {
            text = pages.getPageText( name, readVersion );
        } catch( final ProviderException e ) {
            throw new PublishReadBackException( "Page '" + name + "' version " + savedVersion
                    + " was saved but could not be read back: " + e.getMessage(), savedVersion, e );
        }
        if( text == null ) {
            throw new PublishReadBackException( "Page '" + name + "' version " + savedVersion
                    + " was saved but its text could not be read back", savedVersion, null );
        }
        return text;
    }

    /**
     * The guarded save. A publish on a base version ({@code expectedVersion} above 0) requires the page to still exist,
     * checked under the save lock: a page renamed or deleted away is a {@link VersionConflictException#pageGone} conflict,
     * never recreated (E27/E28). A save that persisted and then failed in its post-save handling is reported as the
     * version it wrote: the page is published, and the failure is logged (ruling I2).
     */
    private SaveOutcome save( final String name, final PublishRequest request, final SaveOptions options )
            throws WikiException {
        final int expected = request.expectedVersion();
        final SaveGuard mustExist = expected > 0 ? ( current, currentText ) -> {
            if( current == null ) {
                throw VersionConflictException.pageGone( expected );
            }
        } : SaveGuard.NONE;
        try {
            return helper.saveTracked( name, request.body(), options, mustExist );
        } catch( final SavedButPostSaveFailedException e ) {
            LOG.warn( "Page '{}' was published as version {}, but the handling after the save failed; reporting it as"
                    + " published: {}", name, e.outcome().version(), e.getMessage(), e );
            return e.outcome();
        }
    }

    private static String lf( final String text ) {
        return text.replace( "\r\n", "\n" ).replace( '\r', '\n' );
    }
}
