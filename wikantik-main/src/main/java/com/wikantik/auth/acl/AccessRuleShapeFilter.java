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

import com.wikantik.api.core.Context;
import com.wikantik.api.exceptions.AccessRuleShapeException;
import com.wikantik.api.filters.PageFilter;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.providers.PageProvider;
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
 * <p>A disputed span the stored page already holds may stay (each stored copy covers one copy in the new text), so an
 * administrator, the only one who can open a locked page, can still save other fixes to it. Which spans are disputed is
 * the reader's decision alone ({@link AccessRules#read}): when it reads a shape as a rule, this filter lets it through.
 */
public class AccessRuleShapeFilter implements PageFilter {

    private static final Logger LOG = LogManager.getLogger( AccessRuleShapeFilter.class );

    /** How to write a rule instead, said in every refusal. */
    static final String CANONICAL = "write `[{ALLOW …}]`; to show the syntax, put it in `code`";

    private static final Map< Shape, String > WHY = new EnumMap<>( Shape.class );

    static {
        WHY.put( Shape.CANONICAL, "it cannot be read" );
        WHY.put( Shape.WRAPPED, "it runs over more than one line" );
        WHY.put( Shape.CASE_VARIANT, "ALLOW or the permission is not in its usual case" );
        WHY.put( Shape.LEADING_SPACE, "there is a space after the opening brace" );
        WHY.put( Shape.ESCAPED, "its opening bracket is escaped" );
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

    /** @param pageManager where the stored version of a page being saved is read from */
    public AccessRuleShapeFilter( final PageManager pageManager ) {
        this( name -> pageManager.wikiPageExists( name ) ? pageManager.getPureText( name, PageProvider.LATEST_VERSION ) : null );
    }

    /** @param storedText the stored latest text of a page by name, {@code null} when there is none */
    AccessRuleShapeFilter( final Function< String, String > storedText ) {
        this.storedText = storedText;
    }

    @Override
    public String preSave( final Context context, final String content ) throws AccessRuleShapeException {
        if ( content == null || content.isEmpty() ) {
            return content;
        }
        final List< Disputed > added = disputed( content );
        if ( added.isEmpty() ) {
            return content;
        }
        final String name = context == null || context.getPage() == null ? null : context.getPage().getName();
        final Map< String, Integer > unchanged = counts( disputed( stored( name ) ) );
        final List< AccessRuleShapeException.Violation > refused = new ArrayList<>();
        for ( final Disputed d : added ) {
            if ( unchanged.merge( d.key(), -1, Integer::sum ) < 0 ) {
                refused.add( violation( d ) );
            }
        }
        if ( refused.isEmpty() ) {
            return content;
        }
        LOG.info( "Refused a save of page {} that adds {} access rule(s) the wiki cannot read", name, refused.size() );
        throw new AccessRuleShapeException( refused );
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
     * @param key what makes two of them the same: the span's text, line ends as {@code \n} (the store may keep
     *            {@code \r\n} where the editor sends {@code \n})
     */
    private record Disputed( String key, Shape shape, int line, String text ) {
    }

    private static List< Disputed > disputed( final String text ) {
        if ( text == null || !( AccessRules.read( text ) instanceof AccessRules.Locked locked ) ) {
            return List.of();
        }
        final List< Disputed > out = new ArrayList<>();
        for ( final AccessRules.Span span : locked.spans() ) {
            if ( AccessRules.disputed( span.shape() ) ) {
                final String spanText = text.substring( span.start(), span.end() );
                out.add( new Disputed( spanText.replace( "\r\n", "\n" ), span.shape(), AccessRules.lineOf( text, span.start() ), excerpt( spanText ) ) );
            }
        }
        if ( out.isEmpty() ) {
            for ( final AccessRules.Reason reason : locked.reasons() ) {
                out.add( new Disputed( reason.shape() + ":" + reason.text(), reason.shape(), reason.line(), reason.text() ) );
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

    private static AccessRuleShapeException.Violation violation( final Disputed d ) {
        final String message = "The access rule on line " + d.line() + " (" + d.text() + ") cannot be read: " + WHY.get( d.shape() )
                + " (" + d.shape() + "). Saving it would lock the page to administrators. To restrict the page, " + CANONICAL + ".";
        return new AccessRuleShapeException.Violation( AccessRuleShapeException.CODE, d.shape().name(), d.line(), d.text(), message );
    }

    private static String excerpt( final String spanText ) {
        return spanText.length() <= AccessRules.MAX_REASON_TEXT ? spanText
                : spanText.substring( 0, AccessRules.MAX_REASON_TEXT ) + "…";
    }
}
