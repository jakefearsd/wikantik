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
import java.util.Map;

/**
 * The autonomy checks on an item write: may this actor move the item to that status, and may it set these policy
 * fields. It resolves the item's effective policy afresh on every call, so a level lowered a moment ago applies
 * to the next request. People pass the status gates without a lookup.
 */
final class ItemGates {

    static final String LOOSER = "Making this looser than it inherits takes a project lead.";

    private final WorkStore store;
    private final WorkScopes scopes;

    ItemGates( final WorkStore store, final WorkScopes scopes ) {
        this.store = store;
        this.scopes = scopes;
    }

    /**
     * Refuses, and records the refusal, a status change the item's level does not let this actor make. Returns
     * whether the change closes the item with no person in its path, so that a person must acknowledge it.
     * {@code target} is the requested status, null for one the caller could not name.
     */
    boolean requireStatusChange( final WorkScopes.Scope s, final Item item, final WorkStatus target )
            throws SQLException {
        if ( s.actor().kind() != Actor.Kind.AGENT ) {
            return false;
        }
        final EffectivePolicy policy = WorkAutonomy.resolve( store.policyChain( item.id() ) );
        final Decision d = WorkPolicy.mayChangeStatus( s.actor(), item, target, policy );
        if ( WorkPolicy.approves( item, target ) ) {
            // a refusal here is the approve gate's; the close gate is only reached once it is passed
            scopes.decide( s, "approve", Action.MOVE, WorkGates.mayApprove( s.actor(), policy ).allowed() ? Decision.allow() : d );
        }
        if ( target == WorkStatus.DONE ) {
            scopes.decide( s, "close", Action.MOVE, d );
        }
        if ( !d.allowed() ) {
            s.refuse( Action.MOVE, d.reason() );
        }
        return target == WorkStatus.DONE && item.status() != WorkStatus.DONE
            && WorkGates.closesWithoutAPerson( s.actor(), policy );
    }

    /**
     * Who may set the policy fields: an agent none of them; done-means and direct commits a lead; a level that is
     * looser than the item has now, a lead; a stricter one, any member who may edit the item.
     */
    void requirePolicyChange( final WorkScopes.Scope s, final Item item, final Map< String, Object > changes )
            throws SQLException {
        final boolean lead = WorkPolicy.may( s.actor(), s.role(), Action.SET_POLICY, item ).allowed();
        if ( lead ) {
            return;
        }
        if ( s.actor().kind() == Actor.Kind.AGENT || changes.containsKey( "doneMeans" )
            || changes.containsKey( "allowDirect" ) ) {
            s.require( Action.SET_POLICY, item );
        }
        final PolicyChain chain = store.policyChain( item.id() );
        final Autonomy named = PatchValues.autonomy( changes.get( "autonomy" ) );
        final Autonomy next = named != null ? named : WorkAutonomy.inherited( chain );
        if ( next.looserThan( WorkAutonomy.uncapped( chain ) ) ) {
            s.refuse( Action.SET_POLICY, LOOSER );
        }
    }
}
