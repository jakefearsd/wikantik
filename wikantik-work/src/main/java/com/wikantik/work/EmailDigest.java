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
package com.wikantik.work;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders one person's pending notices as one plain-text message. A subject never carries a title. Only
 * {@link EmailDetail#FULL} carries comment text; no login appears anywhere, only display names.
 */
final class EmailDigest {

    /** A rendered message. */
    record Email( String subject, String text ) {
    }

    private static final int COMMENT_LIMIT = 500;
    private static final Pattern MENTION = Pattern.compile( "@\\[([^\\]]*)\\]\\(user:[^)]*\\)" );

    private EmailDigest() {
    }

    static Email render( final List< PendingEmail > rows, final EmailDetail detail, final String baseUrl,
                         final Function< String, String > displayName ) {
        return render( rows, detail, baseUrl, displayName, r -> false );
    }

    /**
     * As above; a row {@code asReporter} accepts was reshaped by {@link ReporterEvents} for a person who only reports
     * and is worded with {@link NoticeText#reporterLine}.
     */
    static Email render( final List< PendingEmail > rows, final EmailDetail detail, final String baseUrl,
                         final Function< String, String > displayName, final Predicate< PendingEmail > asReporter ) {
        final List< PendingEmail > ordered = new ArrayList<>( rows );
        ordered.sort( ( a, b ) -> Long.compare( a.notice().id(), b.notice().id() ) );
        final Map< String, List< PendingEmail > > byItem = new LinkedHashMap<>();
        final List< PendingEmail > projectLevel = new ArrayList<>();
        for ( final PendingEmail row : ordered ) {
            final String key = row.notice().event().itemKey();
            if ( key == null ) {
                projectLevel.add( row );
            } else {
                byItem.computeIfAbsent( key, k -> new ArrayList<>() ).add( row );
            }
        }
        final StringBuilder text = new StringBuilder();
        for ( final Map.Entry< String, List< PendingEmail > > item : byItem.entrySet() ) {
            final List< PendingEmail > list = item.getValue();
            text.append( item.getKey() ).append( ' ' ).append( oneLine( list.get( 0 ).notice().itemTitle() ) ).append( '\n' );
            list.forEach( r -> appendNotice( text, r, detail, displayName, asReporter.test( r ) ) );
            text.append( "  " ).append( baseUrl ).append( "/work/item/" ).append( item.getKey() ).append( "\n\n" );
        }
        if ( !projectLevel.isEmpty() ) {
            text.append( projectLevel.get( 0 ).notice().projectKey() ).append( '\n' );
            projectLevel.forEach( r -> appendNotice( text, r, detail, displayName, false ) );
            text.append( '\n' );
        }
        text.append( "You get these because you watch these items, were assigned them or were mentioned.\n" )
            .append( "Choose how often at " ).append( baseUrl ).append( "/work/inbox\n" );
        return new Email( subject( ordered.size(), byItem, projectLevel ), text.toString() );
    }

    private static void appendNotice( final StringBuilder text, final PendingEmail r, final EmailDetail detail,
                                      final Function< String, String > displayName, final boolean asReporter ) {
        final String said = asReporter ? NoticeText.reporterLine( r.notice().event(), displayName )
            : NoticeText.line( r.notice().event(), r.notice().reason(), r.recipient(), displayName );
        text.append( "- " ).append( oneLine( said ) ).append( '\n' );
        if ( detail == EmailDetail.FULL && r.commentText() != null && !r.commentText().isBlank() ) {
            for ( final String line : cut( names( r.commentText() ) ).split( "\\R", -1 ) ) {
                text.append( "    > " ).append( line ).append( '\n' );
            }
        }
    }

    private static String subject( final int count, final Map< String, List< PendingEmail > > byItem,
                                   final List< PendingEmail > projectLevel ) {
        if ( byItem.isEmpty() && projectLevel.stream().allMatch( EmailDigest::switchedOff ) ) {
            return "Work: a webhook was switched off";
        }
        final String updates = count + ( count == 1 ? " update" : " updates" );
        if ( byItem.size() == 1 ) {
            return "Work: " + updates + " on " + byItem.keySet().iterator().next();
        }
        return byItem.isEmpty() ? "Work: " + updates : "Work: " + updates + " on " + byItem.size() + " items";
    }

    private static boolean switchedOff( final PendingEmail r ) {
        final WorkEvent e = r.notice().event();
        return WorkEvent.SETTINGS.equals( e.type() ) && "webhook".equals( e.note() ) && "failed".equals( e.toValue() );
    }

    /** Mention tokens become {@code @Name}. */
    private static String names( final String body ) {
        return MENTION.matcher( body ).replaceAll( m -> Matcher.quoteReplacement( "@" + m.group( 1 ) ) );
    }

    private static String cut( final String s ) {
        final String stripped = s.strip();
        return stripped.length() <= COMMENT_LIMIT ? stripped : stripped.substring( 0, COMMENT_LIMIT ) + "…";
    }

    private static String oneLine( final String s ) {
        return s == null ? "" : s.replace( "\r\n", " " ).replace( '\r', ' ' ).replace( '\n', ' ' );
    }
}
