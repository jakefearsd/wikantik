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

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Gives a block of a page a stable {@code ^id}: locates the block by version and index, tags it through
 * {@link BlockIds#assign} and saves the page through the normal save path (optimistic version check included).
 * A block that already has an id is answered with that id and no save.
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

    public BlockIdService( final PageManager pages, final PageSaveHelper saver ) {
        this.pages = pages;
        this.saver = saver;
    }

    /**
     * Tags the block at {@code index} of the page as it is at {@code expectedVersion}.
     *
     * @param pageName        the page
     * @param expectedVersion the version the caller's block list came from
     * @param index           the block's {@link BlockIds#list} index
     * @param fingerprint     the caller's fingerprint of the block's text; a mismatch is logged, never refused
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
            if ( block.id() != null ) {
                return new Assigned( block.id(), current );
            }
            warnOnDrift( pageName, block, fingerprint, current );
            final String id = BlockIds.newId( takenIds( text ) );
            final String edited = tag( pageName, text, block, id );
            final Page saved = saver.saveText( pageName, edited, SaveOptions.builder()
                    .changeNote( "Added block id ^" + id )
                    .expectedVersion( expectedVersion )
                    .author( author )
                    .build() );
            return new Assigned( id, saved == null ? current + 1 : Math.max( saved.getVersion(), 1 ) );
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
        final List< BlockIds.Block > blocks = BlockIds.list( text );
        if ( index < 0 || index >= blocks.size() ) {
            throw new Refused( Reason.NOT_LOCATED );
        }
        return blocks.get( index );
    }

    /** The fingerprint is a drift diagnostic, not a gate: version and index decide. */
    private static void warnOnDrift( final String pageName, final BlockIds.Block block, final String fingerprint,
                                     final int version ) {
        final String actual = BlockIds.fingerprint( block.plainText() );
        if ( fingerprint != null && !fingerprint.equals( actual ) ) {
            LOG.warn( "Block fingerprint mismatch on '{}' block {} at v{}: client='{}' server='{}'; assigning anyway",
                    pageName, block.index(), version, fingerprint, actual );
        }
    }

    private static Set< String > takenIds( final String text ) {
        final Set< String > taken = new HashSet<>();
        for ( final BlockIds.Block b : BlockIds.list( text ) ) {
            if ( b.id() != null ) {
                taken.add( b.id() );
            }
        }
        return taken;
    }

    private static String tag( final String pageName, final String text, final BlockIds.Block block, final String id )
            throws Refused {
        try {
            return BlockIds.assign( text, block, id );
        } catch ( final IllegalArgumentException e ) {
            LOG.warn( "Block {} of '{}' cannot take an id: {}", block.index(), pageName, e.getMessage() );
            throw new Refused( Reason.NOT_LOCATED );
        } catch ( final IllegalStateException e ) {
            LOG.warn( "Tagging block {} of '{}' with ^{} did not read back: {}", block.index(), pageName, id, e.getMessage() );
            throw e;
        }
    }
}
