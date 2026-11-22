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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Validates new items and applies an {@link ItemPatch} to an existing one. Pure of policy: it knows the data
 * rules, not who may change what. Every failure is a {@link WorkValidationException} naming the field.
 */
final class ItemPatchApplier {

    /** The closed set of keys an {@link ItemPatch} may carry. */
    static final Set< String > KEYS = Set.of( "title", "body", "status", "priority", "rank", "dueOn", "assignee",
        "queue", "parentKey", "productKey", "kind", "type", "labels", "sprint", "estimate", "autonomy", "doneMeans", "allowDirect",
        "before" );

    static final Set< String > POLICY_KEYS = Set.of( "autonomy", "doneMeans", "allowDirect" );
    static final Set< String > MOVE_KEYS = Set.of( "status", "rank", "before" );

    private static final double RANK_STEP = 1024.0;
    private static final String NO_PARENT = "No such item in this project.";

    private final WorkStore store;
    private final ItemTargets targets;

    ItemPatchApplier( final WorkStore store, final ItemTargets.MemberCheck members ) {
        this.store = store;
        this.targets = new ItemTargets( store, members );
    }

    /**
     * A blocker must be another item of the same project. Malformed, unknown, foreign-project and self targets
     * all get the one message, so nothing about other projects can be probed through a link.
     */
    String sameProjectBlocker( final WorkScopes.ItemScope is, final String target ) throws SQLException {
        final String canonical = target.toUpperCase( Locale.ROOT );
        final Item blocker = ItemKeys.PATTERN.matcher( canonical ).matches() ? store.item( canonical ).orElse( null ) : null;
        if ( blocker == null || blocker.projectId() != is.item().projectId() || blocker.id() == is.item().id() ) {
            throw new WorkValidationException( "target", "A blocker must be another item in this project." );
        }
        return blocker.key().toUpperCase( Locale.ROOT );
    }

    /** Builds and validates the item a draft describes, starting at {@code start} ({@link WorkPolicy#startStatus}). */
    Item forCreate( final Project project, final ItemDraft d, final WorkStatus start ) throws SQLException {
        if ( d.level() == null ) {
            throw new WorkValidationException( "level", "An item needs a level." );
        }
        final Item parent = d.parentKey() == null ? null : resolveParent( project, d.parentKey(), -1 );
        final Product product = d.productKey() == null ? null : resolveProduct( d.productKey() );
        final Item next = newItem( project, d, parent, product, start );
        targets.checkNew( project, next );
        ItemRules.validate( next, parent );
        return next;
    }

    /** The unsaved item a draft describes once its parent and product are resolved. */
    private static Item newItem( final Project project, final ItemDraft d, final Item parent, final Product product,
                                 final WorkStatus start ) {
        return new Item( 0, project.id(), null, d.level(), parent == null ? null : parent.id(),
            product == null ? null : product.id(), d.kind() == null ? WorkKind.SOFTWARE : d.kind(), d.title(),
            d.body() == null ? "" : d.body(), start, d.priority() == null ? 3 : d.priority(), 0, d.dueOn(),
            PatchValues.blankToNull( d.assignee() ), null, null, null, null, 0, null, null, null,
            PatchValues.blankToNull( d.queue() ), null, null, null, null,
            d.type() == null ? WorkType.FEATURE : d.type(), Labels.normalise( d.labels() ), null, null );
    }

    /** The item that {@code changes} produce from {@code current}; validated, not yet stored. */
    Item apply( final Project project, final Item current, final Map< String, Object > changes, final String note )
            throws SQLException {
        checkKeys( changes );
        Long parentId = current.parentId();
        Item newParent = null;
        if ( changes.containsKey( "parentKey" ) ) {
            newParent = changedParent( project, current, changes.get( "parentKey" ) );
            parentId = newParent == null ? null : newParent.id();
        }
        final Long productId = changes.containsKey( "productKey" )
            ? changedProduct( changes.get( "productKey" ) ) : current.productId();
        final WorkStatus status = pick( changes, "status", PatchValues::status, current.status() );
        final String queue = targets.changedQueue( current, changes );
        final String assignee = queue != null && changes.containsKey( "queue" ) ? null
            : pick( changes, "assignee", PatchValues::assignee, current.assignee() );
        final double rank = rankFor( project, current, changes, status );
        final Item next = merged( current, changes, parentId, productId, status, rank, assignee, queue );
        ItemRules.validate( next, newParent );
        targets.checkSprint( project, current, next );
        targets.checkAssignee( project, current.assignee(), assignee );
        ItemTargets.requireClaimForQueuedWork( current, next );
        ItemRules.checkHandoffNote( current, next, note );
        return next;
    }

    private static void checkKeys( final Map< String, Object > changes ) {
        for ( final String key : changes.keySet() ) {
            if ( !KEYS.contains( key ) ) {
                throw new WorkValidationException( key, "Unknown field: " + key );
            }
        }
        if ( changes.containsKey( "before" ) ) {
            beforeKey( changes );
        }
    }

    /** The asked-for rank; else the foot of the new column on a status change; else unchanged. */
    private double rankFor( final Project project, final Item current, final Map< String, Object > changes,
                            final WorkStatus status ) throws SQLException {
        if ( changes.containsKey( "rank" ) ) {
            return PatchValues.number( "rank", changes.get( "rank" ) );
        }
        return status != current.status() ? store.maxRank( project.id(), status ) + RANK_STEP : current.rank();
    }

    /** The key a move asks to be placed before, validated as non-blank text; null when the patch has none. */
    static String beforeKey( final Map< String, Object > changes ) {
        final String key = PatchValues.string( "before", changes.get( "before" ) );
        if ( changes.containsKey( "before" ) && ( key == null || key.isBlank() ) ) {
            throw new WorkValidationException( "before", "before must name a card." );
        }
        return key;
    }

    /** The current item with the patched fields replaced; the fields resolved by {@link #apply} come in ready. */
    private static Item merged( final Item current, final Map< String, Object > changes, final Long parentId,
            final Long productId, final WorkStatus status, final double rank, final String assignee,
            final String queue ) {
        return new Item( current.id(), current.projectId(), current.key(), current.level(), parentId, productId,
            pick( changes, "kind", PatchValues::kind, current.kind() ),
            pick( changes, "title", v -> PatchValues.string( "title", v ), current.title() ),
            pick( changes, "body", PatchValues::bodyOf, current.body() ),
            status,
            pick( changes, "priority", PatchValues::priority, current.priority() ),
            rank,
            pick( changes, "dueOn", PatchValues::dueOn, current.dueOn() ),
            assignee, current.reporter(),
            pick( changes, "autonomy", PatchValues::autonomy, current.autonomy() ),
            pick( changes, "doneMeans", PatchValues::doneMeans, current.doneMeans() ),
            pick( changes, "allowDirect", PatchValues::allowDirect, current.allowDirect() ),
            current.version(), current.createdAt(), current.updatedAt(), current.closedAt(),
            queue,
            current.claimedByAgent(), current.claimedAt(), current.claimExpiresAt(), current.reporterAgentId(),
            pick( changes, "type", PatchValues::type, current.type() ),
            pick( changes, "labels", PatchValues::labels, current.labels() ),
            pick( changes, "sprint", PatchValues::sprintId, current.sprintId() ),
            pick( changes, "estimate", PatchValues::estimate, current.estimate() ) );
    }

    private static < T > T pick( final Map< String, Object > changes, final String key,
            final Function< Object, T > coerce, final T current ) {
        return changes.containsKey( key ) ? coerce.apply( changes.get( key ) ) : current;
    }

    private Item changedParent( final Project project, final Item current, final Object value ) throws SQLException {
        final String key = PatchValues.string( "parentKey", value );
        return key == null ? null : resolveParent( project, key, current.id() );
    }

    private Long changedProduct( final Object value ) throws SQLException {
        final String key = PatchValues.string( "productKey", value );
        return key == null ? null : resolveProduct( key ).id();
    }

    /**
     * Resolves a parent key. An unknown item, an item of another project and an item the caller cannot see are
     * indistinguishable by design: the same field and message.
     */
    private Item resolveParent( final Project project, final String key, final long selfId ) throws SQLException {
        final Item parent = store.item( key ).orElse( null );
        if ( parent == null || parent.projectId() != project.id() ) {
            throw new WorkValidationException( "parentKey", NO_PARENT );
        }
        if ( parent.id() == selfId ) {
            throw new WorkValidationException( "parentKey", "An item cannot be its own parent." );
        }
        return parent;
    }

    private Product resolveProduct( final String key ) throws SQLException {
        final Product product = store.product( key ).orElse( null );
        if ( product == null || product.archivedAt() != null ) {
            throw new WorkValidationException( "productKey", "No such product: " + key );
        }
        return product;
    }
}
