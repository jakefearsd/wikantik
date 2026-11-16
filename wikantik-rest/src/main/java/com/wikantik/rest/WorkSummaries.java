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
import com.wikantik.work.Actor;
import com.wikantik.work.WorkService;

import java.util.List;

/** Item lists and board columns whose items carry their card extras beside the item's own fields. */
final class WorkSummaries {

    /** Turns an item into its response tree for a viewer, sharing one name lookup. */
    @FunctionalInterface
    interface Tree {
        JsonElement of( Object value, Actor viewer, WorkLookup lookup );
    }

    private WorkSummaries() {
    }

    static JsonArray list( final List< WorkService.ItemSummary > summaries, final Actor viewer, final WorkLookup lookup,
                           final Tree tree ) {
        final JsonArray out = new JsonArray();
        summaries.forEach( x -> out.add( one( x, viewer, lookup, tree ) ) );
        return out;
    }

    static JsonArray board( final List< WorkService.SummaryColumn > columns, final Actor viewer,
                            final WorkLookup lookup, final Tree tree ) {
        final JsonArray out = new JsonArray();
        for ( final WorkService.SummaryColumn c : columns ) {
            final JsonObject o = new JsonObject();
            o.addProperty( "status", c.status().wire() );
            o.addProperty( "total", c.total() );
            o.add( "items", list( c.items(), viewer, lookup, tree ) );
            out.add( o );
        }
        return out;
    }

    private static JsonObject one( final WorkService.ItemSummary x, final Actor viewer, final WorkLookup lookup,
                                   final Tree tree ) {
        final JsonObject o = tree.of( x.item(), viewer, lookup ).getAsJsonObject();
        o.addProperty( "commentCount", x.commentCount() );
        o.addProperty( "blocked", x.blocked() );
        o.addProperty( "productKey", x.productKey() );
        o.addProperty( "parentKey", x.parentKey() );
        return o;
    }
}
