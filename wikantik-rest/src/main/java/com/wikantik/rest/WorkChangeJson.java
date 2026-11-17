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
import com.google.gson.JsonPrimitive;
import com.wikantik.work.ChangeDraft;
import com.wikantik.work.ItemPolicyView;
import com.wikantik.work.WorkChangesService.ChangeCounts;
import com.wikantik.work.WorkChangesService.ChangeView;
import com.wikantik.work.WorkValidationException;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * The wire form of change records and of an item's policy. A change is its record plus {@code itemKey},
 * {@code productKey} and {@code approvedForHead}; the people on it go through the same naming as everything else
 * ({@link WorkJson}): a non-admin never sees a login.
 */
final class WorkChangeJson {

    private static final Set< String > DRAFT_FIELDS = Set.of( "branch", "baseBranch", "headSha", "reviewUrl",
        "mergedSha" );

    private WorkChangeJson() {
    }

    static JsonObject change( final ChangeView v, final Function< Object, JsonElement > tree ) {
        final JsonObject o = tree.apply( v.change() ).getAsJsonObject();
        o.remove( "itemId" );
        o.remove( "productId" );
        o.addProperty( "itemKey", v.itemKey() );
        o.addProperty( "productKey", v.productKey() );
        o.addProperty( "approvedForHead", v.change().approvedForHead() );
        return o;
    }

    static JsonObject changes( final List< ChangeView > views, final Function< Object, JsonElement > tree ) {
        final JsonArray list = new JsonArray();
        views.forEach( v -> list.add( change( v, tree ) ) );
        final JsonObject out = new JsonObject();
        out.add( "changes", list );
        return out;
    }

    /** What a caller may say when opening a change; any other field is refused by name. */
    static ChangeDraft draft( final JsonObject b ) {
        for ( final String field : b.keySet() ) {
            if ( !DRAFT_FIELDS.contains( field ) ) {
                throw new WorkValidationException( field, "Unknown field: " + field );
            }
        }
        return new ChangeDraft( text( b, "branch" ), text( b, "baseBranch" ), text( b, "headSha" ),
            text( b, "reviewUrl" ), text( b, "mergedSha" ) );
    }

    /** {@code effective} (autonomy, allowDirect, doneMeans) and the acknowledgement state of one item. */
    static JsonObject policy( final ItemPolicyView p, final Function< Object, JsonElement > tree ) {
        final JsonObject out = tree.apply( p.ack() ).getAsJsonObject();
        out.add( "ackRequired", out.remove( "required" ) );
        out.addProperty( "ackWaiting", p.ack().waiting() );
        final JsonObject effective = new JsonObject();
        effective.addProperty( "autonomy", p.effective().level().wire() );
        effective.addProperty( "allowDirect", p.effective().directAllowed() );
        effective.addProperty( "doneMeans", p.effective().doneWhen().wire() );
        out.add( "effective", effective );
        return out;
    }

    static JsonObject counts( final Map< String, ChangeCounts > counts ) {
        final JsonObject out = new JsonObject();
        counts.forEach( ( key, c ) -> {
            final JsonObject o = new JsonObject();
            o.addProperty( "openChanges", c.live() );
            o.addProperty( "inReview", c.inReview() );
            out.add( key, o );
        } );
        return out;
    }

    /** A text field, null when absent; anything that is not text is a 400 on the field. */
    static String text( final JsonObject b, final String field ) {
        final JsonElement v = b.get( field );
        if ( v == null || v.isJsonNull() ) {
            return null;
        }
        if ( !v.isJsonPrimitive() || !( ( JsonPrimitive ) v ).isString() ) {
            throw new WorkValidationException( field, field + " must be text." );
        }
        return v.getAsString();
    }

    /** {@code approved}: true or false, and nothing else. */
    static boolean approved( final JsonObject b ) {
        final JsonElement v = b.get( "approved" );
        if ( v == null || !v.isJsonPrimitive() || !v.getAsJsonPrimitive().isBoolean() ) {
            throw new WorkValidationException( "approved", "approved must be true or false." );
        }
        return v.getAsBoolean();
    }
}
