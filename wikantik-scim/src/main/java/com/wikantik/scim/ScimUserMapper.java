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

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.wikantik.auth.user.UserProfile;

/** Maps {@link UserProfile} to/from the SCIM 2.0 core User schema (Gson). */
public final class ScimUserMapper {

    public static final String SCHEMA_USER = "urn:ietf:params:scim:schemas:core:2.0:User";
    private static final String ATTR_SSO_SUBJECT = "sso.subject";

    private ScimUserMapper() {}

    /** UserProfile → SCIM User JSON. {@code usersBaseUrl} is the collection URL
     *  (e.g. https://host/scim/v2/Users); meta.location = baseUrl + "/" + id. */
    public static JsonObject toScim( final UserProfile p, final String usersBaseUrl ) {
        final JsonObject o = new JsonObject();
        final JsonArray schemas = new JsonArray();
        schemas.add( SCHEMA_USER );
        o.add( "schemas", schemas );
        o.addProperty( "id", p.getUid() );
        o.addProperty( "userName", p.getLoginName() );
        final Object ext = p.getAttributes() == null ? null : p.getAttributes().get( ATTR_SSO_SUBJECT );
        if ( ext != null ) o.addProperty( "externalId", String.valueOf( ext ) );
        if ( p.getFullname() != null ) {
            final JsonObject name = new JsonObject();
            name.addProperty( "formatted", p.getFullname() );
            o.add( "name", name );
        }
        if ( p.getWikiName() != null ) o.addProperty( "displayName", p.getWikiName() );
        if ( p.getEmail() != null ) {
            final JsonArray emails = new JsonArray();
            final JsonObject em = new JsonObject();
            em.addProperty( "value", p.getEmail() );
            em.addProperty( "primary", true );
            emails.add( em );
            o.add( "emails", emails );
        }
        o.addProperty( "active", !p.isLocked() );
        final JsonObject meta = new JsonObject();
        meta.addProperty( "resourceType", "User" );
        meta.addProperty( "location", usersBaseUrl + "/" + p.getUid() );
        o.add( "meta", meta );
        return o;
    }

    /** Fields read from an inbound SCIM User for create/replace. */
    public record CreateFields( String userName, String externalId, String fullName,
                                String email, String displayName, Boolean active, String password ) {}

    public static CreateFields readCreate( final JsonObject in ) {
        final String userName = str( in, "userName" );
        final String externalId = str( in, "externalId" );
        String fullName = null;
        if ( in.has( "name" ) && in.get( "name" ).isJsonObject() ) {
            final JsonObject n = in.getAsJsonObject( "name" );
            fullName = n.has( "formatted" ) ? n.get( "formatted" ).getAsString()
                    : join( str( n, "givenName" ), str( n, "familyName" ) );
        }
        String email = null;
        if ( in.has( "emails" ) && in.get( "emails" ).isJsonArray() && in.getAsJsonArray( "emails" ).size() > 0 ) {
            // Prefer primary:true, else the first.
            for ( final var el : in.getAsJsonArray( "emails" ) ) {
                final JsonObject e = el.getAsJsonObject();
                if ( e.has( "primary" ) && e.get( "primary" ).getAsBoolean() ) { email = str( e, "value" ); break; }
            }
            if ( email == null ) email = str( in.getAsJsonArray( "emails" ).get( 0 ).getAsJsonObject(), "value" );
        }
        final String displayName = str( in, "displayName" );
        final Boolean active = in.has( "active" ) && !in.get( "active" ).isJsonNull()
                ? in.get( "active" ).getAsBoolean() : null;
        final String password = str( in, "password" );
        return new CreateFields( userName, externalId, fullName, email, displayName, active, password );
    }

    private static String str( final JsonObject o, final String k ) {
        return ( o.has( k ) && !o.get( k ).isJsonNull() ) ? o.get( k ).getAsString() : null;
    }
    private static String join( final String a, final String b ) {
        if ( a == null && b == null ) return null;
        return ( ( a == null ? "" : a ) + " " + ( b == null ? "" : b ) ).trim();
    }
}
