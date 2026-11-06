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
package com.wikantik.extractcli;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;

/**
 *  The reader's comment and tag parses of whichever build is on the classpath, looked up <b>by reflection</b>:
 *  comments from {@code ReaderComments} where the build has it, else {@code ObsidianComments}, which every earlier release
 *  has; tags from {@code ReaderTags}, else {@code InlineTagSyntax.normalisedTags}. That is what lets
 *  {@code bin/reader-text-diff.sh} compile {@link ReaderTextSnapshotCli} and this class against an old release's
 *  classpath and against the working tree's, and compare the two.
 */
final class ReaderTextParsers {

    private static final Logger LOG = LogManager.getLogger( ReaderTextParsers.class );
    private static final List< String > COMMENT_CLASSES = List.of(
        "com.wikantik.parser.markdown.ReaderComments", "com.wikantik.api.markdown.ObsidianComments" );
    private static final List< String > TAG_CLASSES = List.of(
        "com.wikantik.parser.markdown.ReaderTags", "com.wikantik.api.markdown.InlineTagSyntax" );
    /** The comment parse of this build, resolved once; null when neither class is on the classpath. */
    private static final Class< ? > COMMENTS = resolve( COMMENT_CLASSES );
    /** The tag parse of this build, resolved once; null when neither class is on the classpath. */
    private static final Class< ? > TAGS = resolve( TAG_CLASSES );

    private ReaderTextParsers() {
    }

    /** The hidden {@code [start,end)} ranges of {@code body}. */
    @SuppressWarnings( "unchecked" )
    static List< int[] > commentRanges( final String body ) {
        return ( List< int[] > ) invoke( COMMENTS, COMMENT_CLASSES, "ranges", body );
    }

    /** {@code body} with its comments removed. */
    static String strip( final String body ) {
        return ( String ) invoke( COMMENTS, COMMENT_CLASSES, "strip", body );
    }

    /** Normalised inline tags of {@code body}, from {@code ReaderTags} where the build has it, else the older scanner. */
    @SuppressWarnings( "unchecked" )
    static Collection< String > normalisedTags( final String body ) {
        final String method = TAGS != null && TAGS.getName().endsWith( "ReaderTags" ) ? "normalised" : "normalisedTags";
        return ( Collection< String > ) invoke( TAGS, TAG_CLASSES, method, body );
    }

    private static Class< ? > resolve( final List< String > candidates ) {
        for ( final String className : candidates ) {
            try {
                final Class< ? > type = Class.forName( className );
                LOG.debug( "parse resolved to {}", className );
                return type;
            } catch ( final ClassNotFoundException absent ) {
                LOG.info( "{} is not on the classpath (an older build); falling back to the next of {}", className, candidates );
            }
        }
        LOG.warn( "none of {} is on the classpath", candidates );
        return null;
    }

    private static Object invoke( final Class< ? > type, final List< String > candidates, final String method, final String body ) {
        if ( type == null ) {
            throw new IllegalStateException( "no parser on the classpath (tried " + candidates + ")" );
        }
        try {
            final Method m = type.getMethod( method, String.class );
            return m.invoke( null, body );
        } catch ( final NoSuchMethodException | IllegalAccessException | InvocationTargetException e ) {
            throw new IllegalStateException( type.getName() + "." + method + " is not callable", e );
        }
    }
}
