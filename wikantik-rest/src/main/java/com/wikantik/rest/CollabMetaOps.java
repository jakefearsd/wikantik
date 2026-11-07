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

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.wikantik.collab.model.MetaEdit;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * The {@code metaOps} of a push (A14, fixture note {@code metaEdits}) as {@link MetaEdit}s, by key presence: a present
 * {@code value} (even null) is a SET, {@code add}/{@code remove} an ADD/REMOVE of one string. Each op is an object
 * with a string {@code field} and exactly one of the three keys; a {@code MetaEdit} that refuses construction (a blank
 * item) is malformed too. Which fields may be edited, and how, is the session's check ({@code MetaEdits.invalid}), made
 * after its identity, rate, freeze, version and text checks.
 */
final class CollabMetaOps {

    private static final Logger LOG = LogManager.getLogger( CollabMetaOps.class );

    /** Plain JSON values for SETs (numbers as doubles; the session normalises values through JSON). */
    private static final Gson VALUES = new Gson();
    private static final List< String > KINDS = List.of( "value", "add", "remove" );

    private CollabMetaOps() {
    }

    /** Adds every op to {@code into} (an absent list adds none); null when all were well formed, else why not. */
    static String parse( final JsonElement list, final List< MetaEdit > into ) {
        if( list == null || list.isJsonNull() ) {
            return null;
        }
        if( !list.isJsonArray() ) {
            return "metaOps must be a list";
        }
        for( final JsonElement e : list.getAsJsonArray() ) {
            final String why = add( e, into );
            if( why != null ) {
                return why;
            }
        }
        return null;
    }

    private static String add( final JsonElement e, final List< MetaEdit > into ) {
        if( e == null || !e.isJsonObject() ) {
            return "a meta op must be an object";
        }
        final JsonObject o = e.getAsJsonObject();
        if( !CollabPush.isString( o.get( "field" ) ) ) {
            return "a meta op needs a field string";
        }
        final List< String > kinds = new ArrayList<>( 1 );
        for( final String k : KINDS ) {
            if( o.has( k ) ) {
                kinds.add( k );
            }
        }
        if( kinds.size() != 1 ) {
            return "exactly one of value, add and remove is required";
        }
        final String field = o.get( "field" ).getAsString();
        final String kind = kinds.get( 0 );
        final JsonElement arg = o.get( kind );
        if( !"value".equals( kind ) && !CollabPush.isString( arg ) ) {
            return "add and remove take a string";
        }
        try {
            into.add( switch( kind ) {
                case "value" -> MetaEdit.set( field, VALUES.fromJson( arg, Object.class ) );
                case "add" -> MetaEdit.add( field, arg.getAsString() );
                default -> MetaEdit.remove( field, arg.getAsString() );
            } );
            return null;
        } catch( final IllegalArgumentException ex ) {
            LOG.debug( "collab: malformed meta op: {}", ex.getMessage() );
            return "malformed meta op: " + ex.getMessage();   // CollabPush.run logs the refusal at WARN
        }
    }
}
