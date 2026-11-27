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

import java.util.Locale;

/**
 * Interprets a SCIM PatchOp (RFC 7644 §3.5.2), supporting the subset IdPs use:
 * add/remove/replace on simple paths. The load-bearing case is toggling
 * {@code active}; other simple attribute changes are surfaced for the resource to
 * apply. Complex value-path filters (e.g. {@code emails[type eq "work"]}) are
 * rejected.
 */
public final class ScimPatchApplier {

    public static final class UnsupportedPatchException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private final String scimType;

        public UnsupportedPatchException( final String m ) { this( "invalidPath", m ); }

        public UnsupportedPatchException( final String scimType, final String m ) {
            super( m );
            this.scimType = scimType;
        }

        /** The RFC 7644 section 3.12 {@code scimType} to answer with. */
        public String scimType() { return scimType; }
    }

    /** What the patch asks for. {@code activeChange} is non-null when the patch
     *  toggles active; {@code attributes} holds other simple replace/add values. */
    public record Result( Boolean activeChange, JsonObject attributes ) {}

    /** Attributes a PATCH {@code remove} may clear. */
    private static final java.util.Set<String> REMOVABLE = java.util.Set.of( "emails", "externalId" );
    /** Attributes that exist but are required or read-only, so cannot be removed. */
    private static final java.util.Set<String> PROTECTED = java.util.Set.of(
            "active", "userName", "displayName", "name", "name.formatted", "id", "meta", "schemas" );

    private ScimPatchApplier() {}

    private static boolean activeValue( final JsonElement value ) {
        if ( value == null || value.isJsonNull() ) {
            throw new UnsupportedPatchException( "invalidValue", "'active' requires a value" );
        }
        return value.getAsBoolean();
    }

    public static Result apply( final JsonObject patchOp ) {
        Boolean activeChange = null;
        final JsonObject attrs = new JsonObject();
        if ( !patchOp.has( "Operations" ) || !patchOp.get( "Operations" ).isJsonArray() ) {
            throw new UnsupportedPatchException( "PatchOp missing Operations array" );
        }
        for ( final JsonElement opEl : patchOp.getAsJsonArray( "Operations" ) ) {
            final JsonObject op = opEl.getAsJsonObject();
            final String operation = op.has( "op" ) ? op.get( "op" ).getAsString().toLowerCase( Locale.ROOT ) : "";
            final String path = op.has( "path" ) && !op.get( "path" ).isJsonNull()
                    ? op.get( "path" ).getAsString() : null;
            if ( path != null && ( path.contains( "[" ) || path.contains( "." ) && path.contains( " " ) ) ) {
                throw new UnsupportedPatchException( "Complex PATCH path not supported: " + path );
            }
            switch ( operation ) {
                case "replace", "add" -> {
                    if ( "active".equals( path ) ) {
                        activeChange = activeValue( op.get( "value" ) );
                    } else if ( path == null && op.has( "value" ) && op.get( "value" ).isJsonObject() ) {
                        // No path: the value object carries the attributes (Entra style).
                        final JsonObject v = op.getAsJsonObject( "value" );
                        if ( v.has( "active" ) ) activeChange = activeValue( v.get( "active" ) );
                        v.entrySet().forEach( e -> { if ( !"active".equals( e.getKey() ) ) attrs.add( e.getKey(), e.getValue() ); } );
                    } else if ( path != null ) {
                        attrs.add( path, op.get( "value" ) );
                    }
                }
                case "remove" -> {
                    // Removing a simple attribute → represent as JSON null. Only attributes that may be
                    // empty can be removed; the rest are required or read-only (RFC 7644 section 3.5.2.3).
                    if ( path != null ) {
                        if ( REMOVABLE.contains( path ) ) {
                            attrs.add( path, com.google.gson.JsonNull.INSTANCE );
                        } else if ( PROTECTED.contains( path ) ) {
                            throw new UnsupportedPatchException( "mutability", "Cannot remove '" + path + "'" );
                        } else {
                            throw new UnsupportedPatchException( "noTarget", "No such attribute to remove: " + path );
                        }
                    }
                }
                default -> throw new UnsupportedPatchException( "Unsupported op: " + operation );
            }
        }
        return new Result( activeChange, attrs );
    }
}
