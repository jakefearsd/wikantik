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
    static final String PIN = "Setting the level an item already inherits takes a project lead.";
    static final String MOVED_LOOSER = "Moving this to where agents may do more takes a project lead.";

    private final WorkStore store;
    private final WorkScopes scopes;

    ItemGates( final WorkStore store, final WorkScopes scopes ) {
        this.store = store;
        this.scopes = scopes;
    }

    /**
     * Refuses, and records the refusal, a status change the item's level does not let this actor make, and says
     * what a move to done must meet: whether it leaves no person in the item's path (so that one must acknowledge
     * it) and whether the item's epic counts it done only in production. {@code target} is the requested status,
     * null for one the caller could not name. A person passes every gate here, but the production rule is about
     * the work, not about who asks, so it is reported for a person too.
     */
    CloseRules requireStatusChange( final WorkScopes.Scope s, final Item item, final WorkStatus target )
            throws SQLException {
        final boolean agent = s.actor().kind() == Actor.Kind.AGENT;
        final boolean closes = target == WorkStatus.DONE && item.status() != WorkStatus.DONE;
        if ( !agent && !closes ) {
            return CloseRules.NONE;
        }
        final EffectivePolicy policy = WorkAutonomy.resolve( store.policyChain( item.id() ) );
        if ( agent ) {
            gate( s, item, target, policy );
        }
        if ( !closes ) {
            return CloseRules.NONE;
        }
        return new CloseRules( WorkGates.closesWithoutAPerson( s.actor(), policy ),
            policy.doneWhen() == DoneMeans.IN_PRODUCTION && item.level() != WorkLevel.EPIC );
    }

    private void gate( final WorkScopes.Scope s, final Item item, final WorkStatus target,
                       final EffectivePolicy policy ) {
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
    }

    /** The gates a patch's fields add to its action: planning a sprint, and changing the autonomy policy. */
    void requireFieldGates( final WorkScopes.Scope s, final Item item, final Map< String, Object > changes )
            throws SQLException {
        if ( changes.containsKey( "sprint" ) ) {
            s.require( Action.PLAN_SPRINT, item );
        }
        if ( changes.keySet().stream().anyMatch( ItemPatchApplier.POLICY_KEYS::contains ) ) {
            requirePolicyChange( s, item, changes );
        }
    }

    /**
     * Who may set the policy fields: an agent none of them; done-means, direct commits and the autonomous level a
     * lead. Anyone else who may edit the item may make it stricter: name a level stricter than the item inherits,
     * tighten or clear a level a lead loosened. They may not loosen it, and may not pin the level it already
     * inherits (an own value outlives a lead tightening the epic).
     */
    void requirePolicyChange( final WorkScopes.Scope s, final Item item, final Map< String, Object > changes )
            throws SQLException {
        if ( WorkPolicy.may( s.actor(), s.role(), Action.SET_POLICY, item ).allowed() ) {
            return;
        }
        final Autonomy named = PatchValues.autonomy( changes.get( "autonomy" ) );
        if ( s.actor().kind() == Actor.Kind.AGENT || changes.containsKey( "doneMeans" )
            || changes.containsKey( "allowDirect" ) || named == Autonomy.AUTONOMOUS ) {
            s.require( Action.SET_POLICY, item );
        }
        final PolicyChain chain = store.policyChain( item.id() );
        final Autonomy own = WorkAutonomy.own( chain );
        final Autonomy inherited = WorkAutonomy.inherited( chain );
        if ( named == null ) {
            if ( own != null && inherited.looserThan( own ) ) {
                s.refuse( Action.SET_POLICY, LOOSER );
            }
            return;
        }
        final boolean tightens = inherited.looserThan( named ) || ( own != null && !named.looserThan( own ) );
        if ( !tightens ) {
            s.refuse( Action.SET_POLICY, named == inherited && own == null ? PIN : LOOSER );
        }
    }

    /**
     * A move to another parent or product that lets agents do more (a looser level, or direct commits), or that
     * takes the item out from under "done means in production", is a loosening like any other: it takes a lead, and an agent never makes it.
     */
    void requireNoLoosening( final WorkScopes.Scope s, final Item current, final Item next ) throws SQLException {
        if ( java.util.Objects.equals( current.parentId(), next.parentId() )
            && java.util.Objects.equals( current.productId(), next.productId() ) ) {
            return;
        }
        final PolicyChain before = store.policyChain( current.id() );
        final EffectivePolicy was = WorkAutonomy.resolve( before );
        final EffectivePolicy willBe = WorkAutonomy.resolve( new PolicyChain( next, ancestorsOf( next ),
            before.project(), productOf( next ) ) );
        // Leaving an epic whose work is done only in production is a loosening too: the item could then close
        // with no deployment behind it.
        final boolean looser = willBe.level().looserThan( was.level() )
            || ( willBe.directAllowed() && !was.directAllowed() )
            || ( was.doneWhen() == DoneMeans.IN_PRODUCTION && willBe.doneWhen() != DoneMeans.IN_PRODUCTION );
        if ( !looser || WorkPolicy.may( s.actor(), s.role(), Action.SET_POLICY, current ).allowed() ) {
            return;
        }
        if ( s.actor().kind() == Actor.Kind.AGENT ) {
            s.require( Action.SET_POLICY, current );
        }
        s.refuse( Action.SET_POLICY, MOVED_LOOSER );
    }

    private java.util.List< Item > ancestorsOf( final Item item ) throws SQLException {
        if ( item.parentId() == null ) {
            return java.util.List.of();
        }
        final PolicyChain parent = store.policyChain( item.parentId() );
        final java.util.List< Item > ancestors = new java.util.ArrayList<>();
        ancestors.add( parent.item() );
        ancestors.addAll( parent.ancestors() );
        return ancestors;
    }

    private Product productOf( final Item item ) throws SQLException {
        if ( item.productId() == null ) {
            return null;
        }
        for ( final Product p : store.products() ) {
            if ( p.id() == item.productId() ) {
                return p;
            }
        }
        return null;
    }
}
