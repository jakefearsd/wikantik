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
package com.wikantik.work;

import java.sql.SQLException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Items an agent closed with no person in their path: reading an item's policy and acknowledgement state,
 * acknowledging one, and the lane of those still waiting. Kept out of {@code WorkService} to hold that class
 * under the complexity limits.
 */
final class Acknowledgements {

    private final WorkStore store;
    private final WorkScopes scopes;
    private final ItemViews views;

    Acknowledgements( final WorkStore store, final WorkScopes scopes, final ItemViews views ) {
        this.store = store;
        this.scopes = scopes;
        this.views = views;
    }

    ItemPolicyView policyOf( final Actor actor, final String key ) throws SQLException {
        final WorkScopes.ItemScope is = scopes.itemScope( actor, key );
        is.scope().require( Action.VIEW, is.item() );
        final PolicyChain chain = store.policyChain( is.item().id() );
        return new ItemPolicyView( WorkAutonomy.resolve( chain ), WorkAutonomy.uncapped( chain ),
            store.acks().state( is.item().id() ) );
    }

    WorkService.ItemView acknowledge( final Actor actor, final String key ) throws SQLException {
        final WorkScopes.ItemScope is = scopes.itemScope( actor, key );
        is.scope().require( Action.ACKNOWLEDGE, is.item() );
        is.scope().requireWritable();
        store.acks().acknowledge( is.item(), actor );
        return views.view( is.scope(), is.item() );
    }

    /** What waits for an acknowledgement in the projects {@code actor} leads; everything for a wiki admin. */
    List< WaitingItem > awaiting( final Actor actor, final List< Project > visible ) throws SQLException {
        final Set< Long > led = new LinkedHashSet<>();
        for ( final Project p : visible ) {
            // an archived project is read-only: nothing in it can be acknowledged, so nothing is asked for
            if ( p.archivedAt() == null && ( actor.instanceAdmin()
                || scopes.standing().roleIn( p.id(), actor ).filter( r -> r == WorkRole.LEAD ).isPresent() ) ) {
                led.add( p.id() );
            }
        }
        return store.acks().awaiting( led ).stream()
            .map( i -> new WaitingItem( i, WaitingItem.ACKNOWLEDGE, null ) ).toList();
    }
}
