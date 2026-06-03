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
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.wikantik.auth.authorize.Group;

import java.security.Principal;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** Maps a {@link Group} to/from the SCIM 2.0 core Group schema (Gson). */
public final class ScimGroupMapper {

    public static final String SCHEMA_GROUP = "urn:ietf:params:scim:schemas:core:2.0:Group";

    private ScimGroupMapper() {}

    /** Group → SCIM Group JSON. {@code loginToUid} resolves a member loginName to its
     *  SCIM uid; members that do not resolve are omitted from the SCIM view. */
    public static JsonObject toScim( final Group group, final String usersBaseUrl,
                                     final String groupsBaseUrl, final Function<String, String> loginToUid ) {
        final JsonObject o = new JsonObject();
        final JsonArray schemas = new JsonArray();
        schemas.add( SCHEMA_GROUP );
        o.add( "schemas", schemas );
        o.addProperty( "id", group.getName() );
        o.addProperty( "displayName", group.getName() );
        final JsonArray members = new JsonArray();
        for ( final Principal p : group.members() ) {
            final String login = p.getName();
            final String uid = loginToUid.apply( login );
            if ( uid == null ) continue;
            final JsonObject m = new JsonObject();
            m.addProperty( "value", uid );
            m.addProperty( "display", login );
            m.addProperty( "$ref", usersBaseUrl + "/" + uid );
            members.add( m );
        }
        o.add( "members", members );
        final JsonObject meta = new JsonObject();
        meta.addProperty( "resourceType", "Group" );
        meta.addProperty( "location", groupsBaseUrl + "/" + group.getName() );
        o.add( "meta", meta );
        return o;
    }

    public static String readDisplayName( final JsonObject in ) {
        return ( in.has( "displayName" ) && !in.get( "displayName" ).isJsonNull() )
                ? in.get( "displayName" ).getAsString() : null;
    }

    /** Member uids referenced in an inbound SCIM Group body. Rejects Group-typed
     *  members (nested groups — out of scope). */
    public static List<String> readMemberUids( final JsonObject in ) {
        final List<String> uids = new ArrayList<>();
        if ( in.has( "members" ) && in.get( "members" ).isJsonArray() ) {
            for ( final JsonElement el : in.getAsJsonArray( "members" ) ) {
                final JsonObject m = el.getAsJsonObject();
                if ( m.has( "type" ) && "Group".equalsIgnoreCase( m.get( "type" ).getAsString() ) ) {
                    throw new NestedGroupUnsupportedException( "Group-typed members are not supported" );
                }
                if ( m.has( "value" ) ) uids.add( m.get( "value" ).getAsString() );
            }
        }
        return uids;
    }

    public static final class NestedGroupUnsupportedException extends RuntimeException {
        public NestedGroupUnsupportedException( final String m ) { super( m ); }
    }
}
