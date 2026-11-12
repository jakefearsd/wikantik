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
import com.google.gson.JsonObject;
import com.wikantik.api.pagegraph.ClusterDetails;
import com.wikantik.api.pagegraph.PageDescriptor;
import com.wikantik.api.pagegraph.TagAggregation;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

/** The members of one cluster a caller may view; {@code hub} is null when it is hidden or absent. */
record VisibleCluster( PageDescriptor hub, List< PageDescriptor > articles ) {

    /** @param viewableOnly drops the descriptors the caller may not view */
    static VisibleCluster of( final ClusterDetails details, final UnaryOperator< List< PageDescriptor > > viewableOnly ) {
        final PageDescriptor hub = details.hubPage() != null && !viewableOnly.apply( List.of( details.hubPage() ) ).isEmpty()
                ? details.hubPage() : null;
        return new VisibleCluster( hub, viewableOnly.apply( details.articles() ) );
    }

    /** The hub (when viewable) and the articles, as one list. */
    List< PageDescriptor > members() {
        return Stream.concat( Stream.ofNullable( hub ), articles.stream() ).toList();
    }

    /** The cluster list entry: counts and freshness over the viewable members only. */
    JsonObject summaryJson( final String name ) {
        final JsonObject o = new JsonObject();
        o.addProperty( "name", name );
        if ( hub != null ) {
            o.add( "hub_page", StructureResource.describe( hub ) );
        }
        o.addProperty( "article_count", articles.size() );
        final Instant updated = updatedAt();
        if ( updated != null ) {
            o.addProperty( "updated_at", updated.toString() );
        }
        return o;
    }

    /** The cluster detail: viewable hub and articles, and the tag distribution over exactly those. */
    JsonObject detailJson( final String name ) {
        final JsonObject data = new JsonObject();
        data.addProperty( "name", name );
        if ( hub != null ) {
            data.add( "hub_page", StructureResource.describe( hub ) );
        }
        final JsonArray list = new JsonArray();
        articles.forEach( p -> list.add( StructureResource.describe( p ) ) );
        data.add( "articles", list );
        final JsonObject tags = new JsonObject();
        TagAggregation.distribution( members() ).forEach( tags::addProperty );
        data.add( "tag_distribution", tags );
        return data;
    }

    boolean empty() {
        return hub == null && articles.isEmpty();
    }

    /** Freshness of the newest viewable member, never of a hidden one. */
    Instant updatedAt() {
        return members().stream().map( PageDescriptor::updated ).filter( Objects::nonNull )
                .max( Comparator.naturalOrder() ).orElse( null );
    }
}
