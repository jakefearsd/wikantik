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

import com.wikantik.parser.markdown.BlockIds;
import com.wikantik.parser.markdown.BlockIds.Block;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Block ids for text that is not saved yet — the editor's buffer. The server's {@link BlockIds} model is the one
 * authority for which block a reader sees where and which id it carries, so the editor asks here instead of reading its
 * own parse: it gets the block the reader shows at an offset, that block's id, or the one edit that gives it a new one.
 * Nothing is saved; the editor applies the edit to its buffer.
 *
 * <p>An id is only ever one the reader shows ({@link Block#id()} is the winning marker's id, never a losing duplicate's
 * or one the reader reads as code), and an added id is checked on the edited text: the block carries it, and every
 * other block keeps the id it had.
 */
public final class BlockDrafts {

    private static final Logger LOG = LogManager.getLogger( BlockDrafts.class );

    /** Why no link could be had. */
    public enum Refusal { NO_BLOCK, REFUSED }

    /** A link that cannot be had: no block at the offset, or a block that cannot take an id. */
    public static final class Refused extends Exception {
        private static final long serialVersionUID = 1L;
        private final Refusal reason;

        public Refused( final Refusal reason, final String message ) {
            super( message );
            this.reason = reason;
        }

        public Refusal reason() {
            return reason;
        }
    }

    /**
     * One edit of the text: {@code [from, to)} replaced by {@code insert}.
     *
     * @param from   start, in the text the link was asked for
     * @param to     end
     * @param insert what goes there
     */
    public record Change( int from, int to, String insert ) {
    }

    /**
     * What linking a block takes.
     *
     * @param id     the id to link to
     * @param change the edit that gives the block that id, or {@code null} when it already carries it
     * @param block  the block, as listed from the text the link was asked for
     */
    public record Link( String id, Change change, Block block ) {
    }

    private BlockDrafts() {
    }

    /**
     * {@link #linkAt(String, String, boolean, int, Function)} of a whole page: {@code text} is the page, frontmatter
     * included.
     */
    public static Link linkAt( final String pageName, final String text, final int offset,
                               final Function< Set< String >, String > newId ) throws Refused {
        return linkAt( pageName, text, false, offset, newId );
    }

    /**
     * The id of the block the reader shows at {@code offset} of {@code text}, and the edit that gives it one if it has
     * none.
     *
     * @param pageName for logging
     * @param text     the unsaved text
     * @param body     whether {@code text} is a page <em>body</em> that the saved page carries behind its frontmatter
     *                 (the editor's buffer when the page has metadata): then a leading {@code ---} pair in it is
     *                 content, read by {@link BlockIds#listBody}; otherwise {@code text} is the whole page, as a save
     *                 without frontmatter stores it, and a leading {@code ---} pair is its frontmatter
     * @param offset   a position in it
     * @param newId    a fresh id, given every id already on the page
     * @return the link
     * @throws Refused {@link Refusal#NO_BLOCK} when no block holds the offset; {@link Refusal#REFUSED} when the block
     *                 cannot take an id, or the edit would change another block's id
     */
    public static Link linkAt( final String pageName, final String text, final boolean body, final int offset,
                               final Function< Set< String >, String > newId ) throws Refused {
        if ( text == null || offset < 0 || offset > text.length() ) {
            throw new Refused( Refusal.NO_BLOCK, "no text at offset " + offset );
        }
        final List< Block > before = list( text, body );
        final Block block = blockAt( before, offset )
                .orElseThrow( () -> new Refused( Refusal.NO_BLOCK, "no block at offset " + offset ) );
        if ( block.id() != null ) {
            return new Link( block.id(), null, block );
        }
        final String id = newId.apply( body ? BlockIds.markerIdsBody( text ) : BlockIds.markerIds( text ) );
        final String after;
        try {
            after = body ? BlockIds.assignBody( text, block, id ) : BlockIds.assign( text, block, id );
        } catch ( final IllegalArgumentException e ) {
            LOG.warn( "Draft of '{}': block {} ({}) cannot take ^{}: {}", pageName, block.index(), block.kind(), id, e.getMessage() );
            throw new Refused( Refusal.REFUSED, e.getMessage() );
        }
        checkOthersKept( pageName, before, list( after, body ), block, id );
        return new Link( id, minimalEdit( text, after ), block );
    }

    /** The blocks of {@code text}, a body ({@link BlockIds#listBody}) or a whole page ({@link BlockIds#list}). */
    public static List< Block > list( final String text, final boolean body ) {
        return body ? BlockIds.listBody( text ) : BlockIds.list( text );
    }

    /** Refuses unless {@code block} carries {@code id} in {@code now} and every other block keeps the id it had. */
    private static void checkOthersKept( final String pageName, final List< Block > before, final List< Block > now,
                                         final Block block, final String id ) throws Refused {
        boolean kept = now.size() == before.size() && id.equals( now.get( block.index() ).id() );
        for ( int i = 0; kept && i < before.size(); i++ ) {
            kept = i == block.index() || Objects.equals( before.get( i ).id(), now.get( i ).id() );
        }
        if ( !kept ) {
            LOG.warn( "Draft of '{}': tagging block {} with ^{} would change other blocks' ids; refused", pageName, block.index(), id );
            throw new Refused( Refusal.REFUSED, "tagging block " + block.index() + " would change other blocks' ids" );
        }
    }

    /**
     * The innermost block holding {@code offset} (its start at or before it, its end at or after it): of those, the one
     * that starts last, and of those the shortest.
     */
    public static Optional< Block > blockAt( final List< Block > blocks, final int offset ) {
        Block best = null;
        for ( final Block b : blocks ) {
            if ( b.start() <= offset && offset <= b.end() && ( best == null || b.start() > best.start()
                    || b.start() == best.start() && b.end() - b.start() < best.end() - best.start() ) ) {
                best = b;
            }
        }
        return Optional.ofNullable( best );
    }

    /** The single edit that turns {@code from} into {@code to}: what lies between their common prefix and suffix. */
    static Change minimalEdit( final String from, final String to ) {
        final int max = Math.min( from.length(), to.length() );
        int prefix = 0;
        while ( prefix < max && from.charAt( prefix ) == to.charAt( prefix ) ) {
            prefix++;
        }
        int suffix = 0;
        while ( suffix < max - prefix && from.charAt( from.length() - 1 - suffix ) == to.charAt( to.length() - 1 - suffix ) ) {
            suffix++;
        }
        return new Change( prefix, from.length() - suffix, to.substring( prefix, to.length() - suffix ) );
    }
}
