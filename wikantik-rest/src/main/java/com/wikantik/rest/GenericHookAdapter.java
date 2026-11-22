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
package com.wikantik.rest;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.wikantik.work.CodeEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Translates the generic JSON format (the wire form of {@link CodeEvent}, camel case, {@code kind} in snake case) into
 * events. The body is one event or an array of events. Anything malformed or unknown is left out; nothing is thrown.
 * Every event must carry {@code at}, the sender's time of the event (ISO-8601 with an offset); an event without a
 * usable one is translated with a null time, and {@link HookIntake} rejects the whole delivery for it. Kinds:
 * {@code push}, {@code review_opened}, {@code review_reopened}, {@code review_ready}, {@code review_updated},
 * {@code review_approved}, {@code review_merged}, {@code review_closed}.
 */
final class GenericHookAdapter {

    private static final Logger LOG = LogManager.getLogger( GenericHookAdapter.class );

    private GenericHookAdapter() {
    }

    /** The events of a delivery body: an object, or an array of objects (at most {@link HookJson#MAX_EVENTS}). */
    static List< CodeEvent > translate( final JsonElement body ) {
        try {
            final List< CodeEvent > out = new ArrayList<>();
            if ( body != null && body.isJsonObject() ) {
                event( body.getAsJsonObject(), out );
            } else if ( body != null && body.isJsonArray() ) {
                int seen = 0;
                for ( final JsonElement element : body.getAsJsonArray() ) {
                    if ( seen++ >= HookJson.MAX_EVENTS ) {
                        break;
                    }
                    if ( element.isJsonObject() ) {
                        event( element.getAsJsonObject(), out );
                    }
                }
            }
            return out;
        } catch ( final RuntimeException e ) {
            LOG.warn( "generic hook translation failed cause={}", e.getClass().getSimpleName() );
            return List.of();
        }
    }

    private static void event( final JsonObject o, final List< CodeEvent > out ) {
        final CodeEvent.Kind kind = kind( HookJson.str( o, "kind", HookJson.NAME ) );
        final String branch = HookJson.str( o, "branch", HookJson.BRANCH );
        if ( kind == null || branch == null ) {
            return;
        }
        out.add( new CodeEvent( kind, HookJson.str( o, "repo", HookJson.NAME ), HookJson.str( o, "defaultBranch", HookJson.BRANCH ),
            branch, HookJson.str( o, "baseBranch", HookJson.BRANCH ), HookJson.str( o, "before", HookJson.SHA ),
            HookJson.str( o, "after", HookJson.SHA ), HookJson.time( o, "at" ), commits( HookJson.arr( o, "commits" ) ),
            review( HookJson.obj( o, "review" ) ), who( o, "actor" ), HookJson.str( o, "mergedSha", HookJson.SHA ) ) );
    }

    private static CodeEvent.Kind kind( final String wire ) {
        if ( wire == null ) {
            return null;
        }
        try {
            return CodeEvent.Kind.valueOf( wire.toUpperCase( Locale.ROOT ) );
        } catch ( final IllegalArgumentException e ) {
            LOG.warn( "generic hook delivery has an unknown kind" );
            return null;
        }
    }

    private static List< CodeEvent.Commit > commits( final JsonArray array ) {
        final List< CodeEvent.Commit > out = new ArrayList<>();
        if ( array == null ) {
            return out;
        }
        for ( final JsonElement element : array ) {
            if ( out.size() >= HookJson.MAX_COMMITS ) {
                break;
            }
            final JsonObject c = element.isJsonObject() ? element.getAsJsonObject() : null;
            final String sha = HookJson.str( c, "sha", HookJson.SHA );
            if ( sha != null ) {
                out.add( new CodeEvent.Commit( sha, HookJson.str( c, "message", HookJson.MESSAGE ), HookJson.time( c, "at" ),
                    HookJson.bool( c, "distinct", true ), who( c, "author" ) ) );
            }
        }
        return out;
    }

    private static CodeEvent.Review review( final JsonObject r ) {
        if ( r == null ) {
            return null;
        }
        return new CodeEvent.Review( HookJson.number( r, "number" ), HookJson.str( r, "url", HookJson.URL ),
            HookJson.str( r, "title", HookJson.TITLE ), HookJson.str( r, "headSha", HookJson.SHA ),
            HookJson.isTrue( r, "draft" ), who( r, "author" ) );
    }

    /** A person written as an object {@code {username,name,email}} or as a bare username string. */
    private static CodeEvent.Person who( final JsonObject o, final String key ) {
        if ( o == null ) {
            return null;
        }
        final JsonObject object = HookJson.obj( o, key );
        return object != null ? HookJson.person( object, "username" ) : HookJson.login( HookJson.str( o, key, HookJson.NAME ) );
    }
}
