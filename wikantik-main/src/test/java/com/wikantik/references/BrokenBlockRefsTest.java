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
package com.wikantik.references;

import com.wikantik.api.managers.PageManager;
import com.wikantik.test.StubPageManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import com.wikantik.wikilink.WikiLinkResolver;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrokenBlockRefsTest {

    private final List< String > warns = new ArrayList<>();
    private AbstractAppender appender;

    @BeforeEach
    void captureLog() {
        appender = new AbstractAppender( "brokenBlockRefsTest", null, null, true, Property.EMPTY_ARRAY ) {
            @Override
            public void append( final LogEvent e ) {
                if ( e.getLevel().isMoreSpecificThan( org.apache.logging.log4j.Level.WARN ) ) {
                    warns.add( e.getMessage().getFormattedMessage() );
                }
            }
        };
        appender.start();
        final org.apache.logging.log4j.core.LoggerContext ctx =
                ( org.apache.logging.log4j.core.LoggerContext ) LogManager.getContext( false );
        final org.apache.logging.log4j.core.config.Configuration config = ctx.getConfiguration();
        final String name = BrokenBlockRefs.class.getName();
        final org.apache.logging.log4j.core.config.LoggerConfig lc =
                org.apache.logging.log4j.core.config.LoggerConfig.newBuilder().setAdditivity( false )
                        .setLevel( org.apache.logging.log4j.Level.WARN ).setLoggerName( name ).setIncludeLocation( "false" )
                        .setRefs( new org.apache.logging.log4j.core.config.AppenderRef[ 0 ] ).setConfig( config ).build();
        lc.addAppender( appender, org.apache.logging.log4j.Level.WARN, null );
        config.addLogger( name, lc );
        ctx.updateLoggers();
    }

    @AfterEach
    void releaseLog() {
        final org.apache.logging.log4j.core.LoggerContext ctx =
                ( org.apache.logging.log4j.core.LoggerContext ) LogManager.getContext( false );
        ctx.getConfiguration().removeLogger( BrokenBlockRefs.class.getName() );
        ctx.updateLoggers();
        appender.stop();
    }

    /** Wraps a StubPageManager, counting and optionally failing text reads. */
    private static PageManager spy( final StubPageManager base, final Map< String, Integer > reads, final String failOn ) {
        return ( PageManager ) java.lang.reflect.Proxy.newProxyInstance( PageManager.class.getClassLoader(),
                new Class< ? >[]{ PageManager.class }, ( proxy, m, args ) -> {
                    if ( m.getName().equals( "getPureText" ) && args.length == 2 ) {
                        reads.merge( ( String ) args[ 0 ], 1, Integer::sum );
                        if ( args[ 0 ].equals( failOn ) ) {
                            throw new IllegalStateException( "boom reading " + failOn );
                        }
                    }
                    try {
                        return m.invoke( base, args );
                    } catch ( final java.lang.reflect.InvocationTargetException e ) {
                        throw e.getCause();
                    }
                } );
    }

    @Test
    void targetTextIsReadOncePerRunHoweverManyLinkToIt() {
        final StubPageManager base = new StubPageManager();
        base.savePage( "A", "[[B#^x]] [[B#^y]]" );
        base.savePage( "C", "[[B#^z]]" );
        base.savePage( "B", "t" );
        final Map< String, Integer > reads = new HashMap<>();
        new BrokenBlockRefs().find( spy( base, reads, null ), resolver( "A", "B", "C" ) );
        // once as a scanned page, once (cached) as the target of three block refs
        assertEquals( 2, reads.get( "B" ) );
    }

    @Test
    void unreadableTargetIsWarnedAndSkippedWhileOthersAreStillReported() {
        final StubPageManager base = new StubPageManager();
        base.savePage( "A", "[[B#^x]] [[E#^nope]]" );
        base.savePage( "C", "[[B#^y]]" );
        base.savePage( "B", "t" );
        base.savePage( "E", "t" );
        final Map< String, Integer > reads = new HashMap<>();
        final List< BrokenBlockRefs.Entry > out =
                new BrokenBlockRefs().find( spy( base, reads, "B" ), resolver( "A", "B", "C", "E" ) );
        assertEquals( List.of( new BrokenBlockRefs.Entry( "A", "E", "nope" ) ), out );
        assertTrue( warns.stream().anyMatch( w -> w.contains( "'B'" ) ), "expected a WARN naming B: " + warns );
    }

    @Test
    void nonexistentTargetIsNotABrokenBlockRefEvenWhenItsTextReadsAsEmpty() {
        final StubPageManager base = new StubPageManager();   // unknown page -> "" (not null)
        base.savePage( "C", "![[Missing#^q]]" );
        assertTrue( new BrokenBlockRefs().find( base, resolver( "C" ) ).isEmpty() );
    }

    private static WikiLinkResolver resolver( final String... pages ) {
        final Set< String > names = Set.of( pages );
        return new WikiLinkResolver( n -> names.contains( n ) ? n : null, Optional::empty );
    }

    @Test
    void reportsMissingIdsOnExistingTargetsAndSamePage() {
        final StubPageManager pm = new StubPageManager();
        pm.savePage( "A", "see [[B#^x]] and [[B#^ok]] and [[#^self]]" );
        pm.savePage( "B", "Para ^ok" );
        pm.savePage( "C", "![[Missing#^q]]" );

        final List< BrokenBlockRefs.Entry > out = new BrokenBlockRefs().find( pm, resolver( "A", "B", "C" ) );

        assertEquals( List.of( new BrokenBlockRefs.Entry( "A", "A", "self" ),
                new BrokenBlockRefs.Entry( "A", "B", "x" ) ), out );
    }

    @Test
    void linksInsideCodeAreIgnoredAndHeadingLinksAreNotBlockRefs() {
        final StubPageManager pm = new StubPageManager();
        pm.savePage( "A", "`[[B#^x]]` and [[B#Heading]] and [[B]]\n\n```\n[[B#^y]]\n```\n" );
        pm.savePage( "B", "text" );
        assertTrue( new BrokenBlockRefs().find( pm, resolver( "A", "B" ) ).isEmpty() );
    }

    @Test
    void sortedAndDeduplicatedAcrossPages() {
        final StubPageManager pm = new StubPageManager();
        pm.savePage( "Z", "[[B#^b]] [[B#^a]] [[B#^a]]" );
        pm.savePage( "A", "[[B#^c]]" );
        pm.savePage( "B", "nothing" );
        final List< BrokenBlockRefs.Entry > out = new BrokenBlockRefs().find( pm, resolver( "A", "B", "Z" ) );
        assertEquals( List.of( new BrokenBlockRefs.Entry( "A", "B", "c" ), new BrokenBlockRefs.Entry( "Z", "B", "a" ),
                new BrokenBlockRefs.Entry( "Z", "B", "b" ) ), out );
    }

    @Test
    void aliasResolvedTargetReportsResolvedPageName() {
        final StubPageManager pm = new StubPageManager();
        pm.savePage( "A", "[[bee#^x]]" );
        pm.savePage( "B", "t ^x" );
        pm.savePage( "D", "[[bee#^nope]]" );
        final WikiLinkResolver r = new WikiLinkResolver( n -> "bee".equals( n ) ? "B" : ( Set.of( "A", "B", "D" ).contains( n ) ? n : null ),
                Optional::empty );
        assertEquals( List.of( new BrokenBlockRefs.Entry( "D", "B", "nope" ) ), new BrokenBlockRefs().find( pm, r ) );
    }
}
