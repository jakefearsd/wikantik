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
package com.wikantik.scim;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Interprets a SCIM Group PatchOp's member operations (RFC 7644 §3.5.2), supporting
 * the subset IdPs send: add / remove / replace on {@code members}, including the
 * {@code members[value eq "<uid>"]} value-path remove and Entra's path-less
 * {@code value} object. Returns the resulting member-uid set.
 */
public final class ScimGroupPatchApplier {

    public static final class UnsupportedGroupPatchException extends RuntimeException {
        public UnsupportedGroupPatchException( final String m ) { super( m ); }
    }

    private static final Pattern MEMBER_VALUE_PATH =
            Pattern.compile( "members\\[\\s*value\\s+eq\\s+\"([^\"]+)\"\\s*\\]" );

    private ScimGroupPatchApplier() {}

    public static LinkedHashSet<String> apply( final Collection<String> currentMemberUids,
                                               final JsonObject patchOp ) {
        final LinkedHashSet<String> members = new LinkedHashSet<>( currentMemberUids );
        if ( !patchOp.has( "Operations" ) || !patchOp.get( "Operations" ).isJsonArray() ) {
            throw new UnsupportedGroupPatchException( "PatchOp missing Operations array" );
        }
        for ( final JsonElement opEl : patchOp.getAsJsonArray( "Operations" ) ) {
            final JsonObject op = opEl.getAsJsonObject();
            final String operation = op.has( "op" ) ? op.get( "op" ).getAsString().toLowerCase( Locale.ROOT ) : "";
            final String path = ( op.has( "path" ) && !op.get( "path" ).isJsonNull() )
                    ? op.get( "path" ).getAsString() : null;
            switch ( operation ) {
                case "add" -> members.addAll( extractMemberValues( op, path ) );
                case "replace" -> {
                    if ( path == null || "members".equals( path ) ) {
                        members.clear();
                        members.addAll( extractMemberValues( op, path ) );
                    } else {
                        throw new UnsupportedGroupPatchException( "replace path not supported: " + path );
                    }
                }
                case "remove" -> {
                    if ( "members".equals( path ) ) {
                        members.clear();
                    } else if ( path != null ) {
                        final Matcher mt = MEMBER_VALUE_PATH.matcher( path );
                        if ( mt.matches() ) members.remove( mt.group( 1 ) );
                        else throw new UnsupportedGroupPatchException( "remove path not supported: " + path );
                    } else {
                        throw new UnsupportedGroupPatchException( "remove requires a path" );
                    }
                }
                default -> throw new UnsupportedGroupPatchException( "Unsupported op: " + operation );
            }
        }
        return members;
    }

    /** Member uids from an op's value: an array of {value:…}, or a path-less value
     *  object carrying {members:[…]} (Entra), or a bare {value:…}. */
    private static List<String> extractMemberValues( final JsonObject op, final String path ) {
        final List<String> out = new ArrayList<>();
        final JsonElement value = op.get( "value" );
        if ( value == null || value.isJsonNull() ) return out;
        if ( value.isJsonArray() ) {
            for ( final JsonElement el : value.getAsJsonArray() ) out.add( memberValue( el ) );
        } else if ( value.isJsonObject() ) {
            final JsonObject vo = value.getAsJsonObject();
            if ( vo.has( "members" ) && vo.get( "members" ).isJsonArray() ) {
                for ( final JsonElement el : vo.getAsJsonArray( "members" ) ) out.add( memberValue( el ) );
            } else if ( vo.has( "value" ) ) {
                out.add( vo.get( "value" ).getAsString() );
            } else {
                throw new UnsupportedGroupPatchException( "Unsupported value object for path: " + path );
            }
        }
        return out;
    }

    private static String memberValue( final JsonElement el ) {
        if ( el.isJsonObject() && el.getAsJsonObject().has( "value" ) ) {
            return el.getAsJsonObject().get( "value" ).getAsString();
        }
        return el.getAsString();
    }
}
