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
package com.wikantik.content;

import com.wikantik.api.core.Page;
import com.wikantik.api.exceptions.ProviderException;
import com.wikantik.api.exceptions.WikiException;
import com.wikantik.api.frontmatter.FrontmatterParser;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.pages.PageSaveHelper;
import com.wikantik.api.pages.SaveOptions;
import com.wikantik.api.pages.VersionConflictException;
import com.wikantik.api.providers.PageProvider;
import com.wikantik.derived.DerivedPage;
import com.wikantik.parser.markdown.BlockIds;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * Gives a block of a page a stable {@code ^id}: locates the block by version and index, tags it through
 * {@link BlockIds#assign} and saves the page through the normal save path (optimistic version check included).
 * A block that already has an id is answered with that id and no save — unless the caller's fingerprint says it was
 * looking at another block (see {@link #assign}).
 */
public final class BlockIdService {

    private static final Logger LOG = LogManager.getLogger( BlockIdService.class );

    /** Why an assignment was refused. */
    public enum Reason { STALE, NOT_LOCATED, DERIVED }

    /** The id the block now carries and the page version that holds it. */
    public record Assigned( String id, int version ) {
    }

    /** An assignment that cannot be done; the handler maps {@link #reason()} to a 409 body. */
    public static final class Refused extends WikiException {
        private static final long serialVersionUID = 1L;
        private final Reason reason;

        public Refused( final Reason reason ) {
            super( "Block id assignment refused: " + reason );
            this.reason = reason;
        }

        public Reason reason() {
            return reason;
        }
    }

    private static final int STRIPES = 64;
    private static final Object[] LOCKS = new Object[ STRIPES ];
    static {
        for ( int i = 0; i < STRIPES; i++ ) {
            LOCKS[ i ] = new Object();
        }
    }

    private final PageManager pages;
    private final PageSaveHelper saver;
    private final Function< Set< String >, String > newId;

    public BlockIdService( final PageManager pages, final PageSaveHelper saver ) {
        this( pages, saver, BlockIds::newId );
    }

    /** Test seam: {@code newId} picks a fresh id not in the set it is given (as {@link BlockIds#newId} does). */
    BlockIdService( final PageManager pages, final PageSaveHelper saver, final Function< Set< String >, String > newId ) {
        this.pages = pages;
        this.saver = saver;
        this.newId = newId;
    }

    /**
     * Tags the block at {@code index} of the page as it is at {@code expectedVersion}.
     *
     * @param pageName        the page
     * @param expectedVersion the version the caller's block list came from
     * @param index           the block's {@link BlockIds#list} index
     * @param fingerprint     the caller's fingerprint of the block's text (normalised here; blank means none). On a block
     *                        without an id a mismatch is only logged; on one that already has an id it is refused as
     *                        {@code NOT_LOCATED}, since the caller saw no id there
     * @param author          the save's author (may be {@code null})
     * @throws Refused                  {@code NOT_LOCATED} (no such page or block) or {@code DERIVED}
     * @throws VersionConflictException when the page is no longer at {@code expectedVersion}
     * @throws WikiException            on a failed save
     */
    public Assigned assign( final String pageName, final int expectedVersion, final int index,
                            final String fingerprint, final String author ) throws WikiException {
        synchronized ( LOCKS[ Math.floorMod( pageName.hashCode(), STRIPES ) ] ) {
            final Page page = pages.getPageWithoutMetadata( pageName, PageProvider.LATEST_VERSION );
            if ( page == null ) {
                throw new Refused( Reason.NOT_LOCATED );
            }
            final int current = Math.max( page.getVersion(), 1 );
            if ( current != expectedVersion ) {
                throw new VersionConflictException( pageName, current, expectedVersion, false );
            }
            final String text = pages.getPureText( pageName, PageProvider.LATEST_VERSION );
            final BlockIds.Block block = locate( pageName, text, index );
            final String sent = fingerprint == null || fingerprint.isBlank() ? null : BlockIds.fingerprint( fingerprint );
            final String actual = BlockIds.fingerprint( block.plainText() );
            if ( block.id() != null ) {
                return existing( pageName, block, sent, actual, current );
            }
            if ( sent != null && !sent.equals( actual ) ) {   // a diagnostic, not a gate: version and index decide
                LOG.warn( "Block fingerprint mismatch on '{}' block {} at v{}: client='{}' server='{}'; assigning anyway",
                        pageName, block.index(), current, sent, actual );
            }
            final String id = newId.apply( takenIds( pageName, text ) );
            final String edited = tag( pageName, text, block, id );
            final Page saved = saver.saveText( pageName, edited, SaveOptions.builder()
                    .changeNote( "Added block id ^" + id )
                    .expectedVersion( expectedVersion )
                    .author( author )
                    .build() );
            if ( saved == null ) {   // e.g. vetoed by a filter: not a success, whatever the block looks like
                LOG.warn( "Saving block id ^{} on '{}' did not persist a page (saveText returned null)", id, pageName );
                throw new ProviderException( "Page save did not persist" );
            }
            return new Assigned( id, Math.max( saved.getVersion(), 1 ) );
        }
    }

    /** The block at {@code index}, or a refusal: no text, a derived page, or an index out of range. */
    private static BlockIds.Block locate( final String pageName, final String text, final int index ) throws Refused {
        if ( text == null ) {
            throw new Refused( Reason.NOT_LOCATED );
        }
        if ( DerivedPage.isDerived( FrontmatterParser.parse( text ).metadata() ) ) {
            throw new Refused( Reason.DERIVED );
        }
        final List< BlockIds.Block > blocks;
        try {
            blocks = BlockIds.list( text );
        } catch ( final RuntimeException e ) {
            LOG.warn( "The blocks of '{}' could not be read ({}); block {} is not located", pageName, e.toString(), index, e );
            throw new Refused( Reason.NOT_LOCATED );
        }
        if ( index < 0 || index >= blocks.size() ) {
            throw new Refused( Reason.NOT_LOCATED );
        }
        return blocks.get( index );
    }

    /**
     * A block that already has an id. At the version the caller listed, a caller that saw no id there and sent other
     * text was looking at another block: the numbering drifted, so its id is not handed out.
     */
    private static Assigned existing( final String pageName, final BlockIds.Block block, final String sent,
                                      final String actual, final int version ) throws Refused {
        if ( sent != null && !sent.equals( actual ) ) {
            LOG.warn( "Block {} of '{}' at v{} already has id ^{} but the client sent other text (client='{}' server='{}'); "
                    + "refusing: the block numbering drifted", block.index(), pageName, version, block.id(), sent, actual );
            throw new Refused( Reason.NOT_LOCATED );
        }
        return new Assigned( block.id(), version );
    }

    private static String tag( final String pageName, final String text, final BlockIds.Block block, final String id )
            throws Refused {
        try {
            return BlockIds.assign( text, block, id );
        } catch ( final IllegalArgumentException e ) {
            LOG.warn( "Block {} of '{}' cannot take an id: {}", block.index(), pageName, e.getMessage() );
            throw new Refused( Reason.NOT_LOCATED );
        } catch ( final RuntimeException e ) {
            LOG.warn( "Tagging block {} of '{}' with ^{} failed ({}); block not located", block.index(), pageName, id, e.toString(), e );
            throw new Refused( Reason.NOT_LOCATED );
        }
    }

    /** Every marker id on the page, so a new id collides with none; a text whose markers cannot be read is refused. */
    private static java.util.Set< String > takenIds( final String pageName, final String text ) throws Refused {
        try {
            return BlockIds.markerIds( text );
        } catch ( final RuntimeException e ) {
            LOG.warn( "The block markers of '{}' could not be read ({}); block not located", pageName, e.toString(), e );
            throw new Refused( Reason.NOT_LOCATED );
        }
    }
}
