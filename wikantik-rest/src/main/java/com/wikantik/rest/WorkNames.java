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

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.Map;
import java.util.function.Function;

/**
 * The name twins {@link WorkJson#enrich} adds beside a person, an agent, a member or an item's claim and queue: the
 * login or agent a stored text stands for, shown by display name or (current) agent name.
 */
final class WorkNames {

    private WorkNames() {
    }

    /** The id of the agent that wrote the value under {@code key}, or null when a person did. */
    static Long agentIdBehind( final JsonObject o, final String key ) {
        final String idField = switch ( key ) {
            case "author" -> "authorAgentId";
            case "reporter" -> "reporterAgentId";
            case "editedBy" -> "editedByAgentId";
            case "openedBy" -> "openedByAgentId";
            case "reviewedBy" -> "reviewedByAgentId";
            case "mergedBy" -> "mergedByAgentId";
            case "actor" -> "agent".equals( WorkJson.str( o, "actorKind" ) ) ? "agentId" : null;
            default -> null;
        };
        return idField == null ? null : WorkJson.number( o, idField );
    }

    /** {@code key} holds a login: add {@code keyName}, and for a non-admin replace the login with the name. */
    static void person( final Map< String, JsonElement > changes, final String key, final String login,
                                final Function< String, String > names, final WorkViewer viewer ) {
        final JsonPrimitive name = new JsonPrimitive( names.apply( login ) );
        changes.put( key + "Name", name );
        kind( changes, key, "human" );
        if ( "author".equals( key ) ) {
            changes.put( "mine", new JsonPrimitive( viewer.isPerson( login ) ) );
        }
        if ( !viewer.admin() ) {
            changes.put( key, name );
        }
    }

    /** {@code key} holds an agent's name as stored at write time; shown by the agent's current name. */
    static void agent( final Map< String, JsonElement > changes, final String key, final String stored,
                               final long agentId, final WorkLookup lookup, final WorkViewer viewer ) {
        final String resolved = lookup.agent( agentId );
        final JsonPrimitive name = new JsonPrimitive( resolved != null ? resolved : stored );
        changes.put( key + "Name", name );
        kind( changes, key, "agent" );
        if ( "author".equals( key ) ) {
            changes.put( "mine", new JsonPrimitive( viewer.isAgent( agentId ) ) );
        }
        if ( !viewer.admin() ) {
            changes.put( key, name );
        }
    }

    /** {@code authorKind}, {@code reporterKind}, {@code editedByKind}; an event already carries {@code actorKind}. */
    static void kind( final Map< String, JsonElement > changes, final String key, final String kind ) {
        if ( !"actor".equals( key ) && !"assignee".equals( key ) ) {
            changes.put( key + "Kind", new JsonPrimitive( kind ) );
        }
    }

    static void memberNames( final JsonObject o, final Map< String, JsonElement > changes,
                                     final Function< String, String > names, final WorkViewer viewer,
                                     final WorkLookup lookup ) {
        final String principal = WorkJson.str( o, "principal" );
        if ( principal == null || !o.has( "principalKind" ) ) {
            return;
        }
        final String kind = WorkJson.str( o, "principalKind" );
        if ( "agent".equals( kind ) ) {   // the stored principal is the agent's id
            final String name = WorkEventNames.agentLabel( lookup, principal );
            changes.put( "displayName", new JsonPrimitive( name ) );
            changes.put( "principal", new JsonPrimitive( name ) );
            changes.put( "self", new JsonPrimitive( WorkEventNames.parseId( principal ) != null
                && viewer.isAgent( WorkEventNames.parseId( principal ) ) ) );
            return;
        }
        final boolean user = "user".equals( kind );
        final String name = user ? names.apply( principal ) : principal;
        changes.put( "displayName", new JsonPrimitive( name ) );
        changes.put( "self", new JsonPrimitive( user && viewer.isPerson( principal ) ) );
        if ( user && !viewer.admin() ) {
            changes.put( "principal", new JsonPrimitive( name ) );
        }
    }

    /** An item's {@code claimedByName} and {@code queueName}; neither is confidential. */
    static void claimAndQueueNames( final JsonObject o, final Map< String, JsonElement > changes,
                                            final WorkLookup lookup ) {
        if ( !o.has( "claimedByAgent" ) ) {
            return;
        }
        final Long holder = WorkJson.number( o, "claimedByAgent" );
        if ( holder != null ) {
            final String name = lookup.agent( holder );
            changes.put( "claimedByName", new JsonPrimitive( name == null ? WorkJson.UNKNOWN_AGENT : name ) );
        }
        final String queue = WorkJson.str( o, "queue" );
        if ( queue != null ) {
            changes.put( "queueName", new JsonPrimitive( WorkEventNames.queueLabel( lookup, queue ) ) );
        }
    }
}
