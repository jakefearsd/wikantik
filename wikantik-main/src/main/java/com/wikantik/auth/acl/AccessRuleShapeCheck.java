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
package com.wikantik.auth.acl;

import com.wikantik.api.exceptions.AccessRuleShapeException;
import com.wikantik.auth.acl.AccessRules.Shape;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Refuses a save that adds an access rule {@link AccessRules} cannot read. Such a span locks the page to administrators,
 * so without this any editor could lock a page out, themselves included, by typing <code>[{DENY …}]</code>.
 *
 * <p>It checks the text that is stored: the save path runs it after the whole pre-save filter chain, because filters
 * rewrite the text (the structural spine and the frontmatter defaults re-emit the frontmatter, which can spell out a rule
 * the text as typed only escaped).
 *
 * <p>A disputed span the stored page already holds may stay (each stored copy covers one copy in the new text), so an
 * administrator, the only one who can open a locked page, can still save other fixes to it. Which spans are disputed is
 * the reader's decision alone ({@link AccessRules#read}): when it reads a shape as a rule, this check lets it through.
 */
public class AccessRuleShapeCheck {

    private static final Logger LOG = LogManager.getLogger( AccessRuleShapeCheck.class );

    /** How to write a rule instead, said in every refusal. */
    static final String CANONICAL = "write `[{ALLOW …}]`; to show the syntax, put it in `code`";

    /** The key of a whole text the render parse cannot read: its shape alone, as the reason quotes offsets and sizes. */
    private static final String WHOLE_TEXT_KEY = "whole text:";

    /** Why each shape that locks a page cannot be read, as a refusal says it. */
    static final Map< Shape, String > WHY = new EnumMap<>( Shape.class );

    static {
        // Only the shapes that lock a page: the reader reads every other shape as a rule, or as text, and so lets it save.
        WHY.put( Shape.WRAPPED_NAME, "a line break falls inside a name with no comma beside it, so the wiki cannot tell one "
                + "name or two (`Jane` and `Bob`, or `Jane Bob`); put a comma at the break, or the name on one line" );
        WHY.put( Shape.DENY, "access rules only allow: there is no DENY" );
        WHY.put( Shape.ALLOW_PREFIXED_WORD, "its first word is not exactly ALLOW" );
        WHY.put( Shape.IN_HTML_BLOCK, "it is inside HTML" );
        WHY.put( Shape.IN_MATH, "it is inside math" );
        WHY.put( Shape.IN_FRONTMATTER, "it is in the frontmatter, not the page body" );
        WHY.put( Shape.LINK_WITH_TARGET, "it is a link with a target, or an image" );
        WHY.put( Shape.BAD_PERMISSION, "it does not name a page permission (view, comment, edit, modify, upload, rename, delete)" );
        WHY.put( Shape.UNREADABLE, "the page text cannot be read the way the renderer reads it" );
    }

    /** The stored latest text of a page by name; {@code null} for a page not stored yet. */
    private final Function< String, String > storedText;

    /** @param storedText the stored latest text of a page by name, {@code null} when there is none */
    public AccessRuleShapeCheck( final Function< String, String > storedText ) {
        this.storedText = storedText;
    }

    /**
     * @param pageName the page being saved; {@code null} when unknown (then nothing counts as stored)
     * @param text     the text about to be stored
     * @throws AccessRuleShapeException when the text adds a disputed span the stored page does not hold
     */
    public void check( final String pageName, final String text ) throws AccessRuleShapeException {
        if ( text == null || text.isEmpty() ) {
            return;
        }
        final List< Disputed > added = disputed( text );
        if ( added.isEmpty() ) {
            return;
        }
        final Map< String, Integer > unchanged = counts( disputed( stored( pageName ) ) );
        final AccessRules.Places places = new AccessRules.Places( text );
        final List< AccessRuleShapeException.Violation > refused = new ArrayList<>();
        int total = 0;
        for ( final Disputed d : added ) {
            if ( unchanged.merge( d.key(), -1, Integer::sum ) < 0 ) {
                total++;
                // Only the violations a refusal keeps are spelled out: a page of openers holds one per few characters.
                if ( refused.size() < AccessRuleShapeException.MAX_VIOLATIONS ) {
                    refused.add( violation( text, places, d ) );
                }
            }
        }
        if ( refused.isEmpty() ) {
            return;
        }
        LOG.info( "Refused a save of page {} that adds {} access rule(s) the wiki cannot read", pageName, total );
        throw new AccessRuleShapeException( refused, total );
    }

    private String stored( final String name ) {
        if ( name == null ) {
            return null;
        }
        try {
            return storedText.apply( name );
        } catch ( final RuntimeException e ) {
            // Counting nothing as stored refuses every disputed span: the fail-closed side.
            LOG.warn( "Could not read the stored text of page {} to compare its access rules; treating it as holding none: {}",
                    name, e.toString() );
            return null;
        }
    }

    /**
     * A disputed span of a text, or the whole text when it is unreadable with no span of its own.
     *
     * @param key         what makes two of them the same: the span's text, line ends as {@code \n} (the store may keep
     *                    {@code \r\n} where the editor sends {@code \n}); for the whole text, its shape alone
     * @param span        the span; {@code null} when it stands for the whole text
     * @param wholeReason what is wrong with the whole text; {@code null} for a span (its reason is worked out only when a
     *                    refusal cites it)
     */
    private record Disputed( String key, AccessRules.Span span, AccessRules.Reason wholeReason ) {
    }

    /**
     * The disputed spans of a text. Spans that overlap (openers that share one closer: <code>[{deny a [{deny b}]</code>)
     * share one key, the text they cover together: a copy of each span's own text would cost the square of the text.
     * Such a group stays only when it is unchanged as a whole.
     */
    private static List< Disputed > disputed( final String text ) {
        if ( text == null || !( AccessRules.read( text ) instanceof AccessRules.Locked locked ) ) {
            return List.of();
        }
        final List< AccessRules.Span > spans = locked.spans().stream().filter( span -> AccessRules.disputed( span.shape() ) ).toList();
        final List< Disputed > out = new ArrayList<>();
        int group = 0;
        while ( group < spans.size() ) {
            int groupEnd = spans.get( group ).end();
            int next = group + 1;
            while ( next < spans.size() && spans.get( next ).start() < groupEnd ) {
                groupEnd = Math.max( groupEnd, spans.get( next ).end() );
                next++;
            }
            final String key = text.substring( spans.get( group ).start(), groupEnd ).replace( "\r\n", "\n" );
            for ( int i = group; i < next; i++ ) {
                out.add( new Disputed( key, spans.get( i ), null ) );
            }
            group = next;
        }
        if ( out.isEmpty() ) {
            for ( final AccessRules.Reason reason : locked.reasons() ) {
                out.add( new Disputed( WHOLE_TEXT_KEY + reason.shape(), null, reason ) );
            }
        }
        return out;
    }

    private static Map< String, Integer > counts( final List< Disputed > spans ) {
        final Map< String, Integer > out = new HashMap<>();
        for ( final Disputed d : spans ) {
            out.merge( d.key(), 1, Integer::sum );
        }
        return out;
    }

    private static AccessRuleShapeException.Violation violation( final String text, final AccessRules.Places places, final Disputed d ) {
        final AccessRules.Span span = d.span();
        final AccessRules.Reason r = span == null ? d.wholeReason() : places.reason( span.shape(), span.start(), excerpt( text, span ) );
        final String what = span == null ? capitalised( r.text() ) + " (" + r.shape() + ")."
                : "The access rule " + where( r ) + " (" + r.text() + ") cannot be read: " + WHY.get( r.shape() )
                        + " (" + r.shape() + ").";
        final String message = what + " Saving it would lock the page to administrators. To restrict the page, " + CANONICAL + ".";
        return new AccessRuleShapeException.Violation( AccessRuleShapeException.CODE, r.shape().name(), r.line(), r.field(),
                r.text(), message );
    }

    private static String where( final AccessRules.Reason r ) {
        if ( r.line() != null ) {
            return "on line " + r.line();
        }
        return r.field() != null ? "in the frontmatter field `" + r.field() + "`" : "in the frontmatter";
    }

    private static String capitalised( final String s ) {
        return s.isEmpty() ? s : Character.toUpperCase( s.charAt( 0 ) ) + s.substring( 1 );
    }

    private static String excerpt( final String text, final AccessRules.Span span ) {
        return span.end() - span.start() <= AccessRules.MAX_REASON_TEXT ? text.substring( span.start(), span.end() )
                : text.substring( span.start(), span.start() + AccessRules.MAX_REASON_TEXT ) + "…";
    }
}
