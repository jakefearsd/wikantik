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
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Validates new items and applies an {@link ItemPatch} to an existing one. Pure of policy: it knows the data
 * rules, not who may change what. Every failure is a {@link WorkValidationException} naming the field.
 */
final class ItemPatchApplier {

    /** The closed set of keys an {@link ItemPatch} may carry. */
    static final Set< String > KEYS = Set.of( "title", "body", "status", "priority", "rank", "dueOn", "assignee",
        "parentKey", "productKey", "kind", "autonomy", "doneMeans", "allowDirect" );

    static final Set< String > POLICY_KEYS = Set.of( "autonomy", "doneMeans", "allowDirect" );
    static final Set< String > MOVE_KEYS = Set.of( "status", "rank" );

    static final int MAX_TITLE = 200;
    private static final double RANK_STEP = 1024.0;
    private static final String NO_PARENT = "No such item in this project.";

    /** Whether a login holds a role on a project; the service resolves it through the directory's groups. */
    @FunctionalInterface
    interface MemberCheck {
        boolean isMember( long projectId, String login ) throws SQLException;
    }

    private final WorkStore store;
    private final MemberCheck members;

    ItemPatchApplier( final WorkStore store, final MemberCheck members ) {
        this.store = store;
        this.members = members;
    }

    /** Builds and validates the item a draft describes; status starts as draft. */
    Item forCreate( final Project project, final ItemDraft d ) throws SQLException {
        if ( d.level() == null ) {
            throw new WorkValidationException( "level", "An item needs a level." );
        }
        final Item parent = d.parentKey() == null ? null : resolveParent( project, d.parentKey(), -1 );
        final Product product = d.productKey() == null ? null : resolveProduct( d.productKey() );
        final int priority = d.priority() == null ? 3 : d.priority();
        final String assignee = PatchValues.blankToNull( d.assignee() );
        final Item next = new Item( 0, project.id(), null, d.level(), parent == null ? null : parent.id(),
            product == null ? null : product.id(), d.kind() == null ? WorkKind.SOFTWARE : d.kind(), d.title(),
            d.body() == null ? "" : d.body(), WorkStatus.DRAFT, priority, 0, d.dueOn(), assignee, null, null, null,
            null, 0, null, null, null );
        validate( next, parent );
        checkAssignee( project, null, assignee );
        return next;
    }

    /** The item that {@code changes} produce from {@code current}; validated, not yet stored. */
    Item apply( final Project project, final Item current, final Map< String, Object > changes, final String note )
            throws SQLException {
        for ( final String key : changes.keySet() ) {
            if ( !KEYS.contains( key ) ) {
                throw new WorkValidationException( key, "Unknown field: " + key );
            }
        }
        Long parentId = current.parentId();
        Item newParent = null;
        if ( changes.containsKey( "parentKey" ) ) {
            newParent = changedParent( project, current, changes.get( "parentKey" ) );
            parentId = newParent == null ? null : newParent.id();
        }
        final Long productId = changes.containsKey( "productKey" )
            ? changedProduct( changes.get( "productKey" ) ) : current.productId();
        final WorkStatus status = pick( changes, "status", PatchValues::status, current.status() );
        final String assignee = pick( changes, "assignee", PatchValues::assignee, current.assignee() );
        final double rank = changes.containsKey( "rank" ) ? PatchValues.number( "rank", changes.get( "rank" ) )
            : status != current.status() ? store.maxRank( project.id(), status ) + RANK_STEP : current.rank();
        final Item next = merged( current, changes, parentId, productId, status, rank, assignee );
        validate( next, newParent );
        checkAssignee( project, current.assignee(), assignee );
        checkHandoffNote( current, next, note );
        return next;
    }

    /** The current item with the patched fields replaced; the fields resolved by {@link #apply} come in ready. */
    private static Item merged( final Item current, final Map< String, Object > changes, final Long parentId,
            final Long productId, final WorkStatus status, final double rank, final String assignee ) {
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
            current.version(), current.createdAt(), current.updatedAt(), current.closedAt() );
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

    private static void checkHandoffNote( final Item current, final Item next, final String note ) {
        if ( next.status() != current.status() && !Objects.equals( next.assignee(), current.assignee() )
                && ( note == null || note.isBlank() ) ) {
            throw new WorkValidationException( "note", "Changing status and assignee together needs a note." );
        }
    }

    /** Field rules; {@code newParent} is the freshly resolved parent, null when the parent is not being set. */
    private static void validate( final Item next, final Item newParent ) {
        validateTitle( next.title() );
        validateShape( next, newParent );
        if ( next.priority() < 1 || next.priority() > 4 ) {
            throw new WorkValidationException( "priority", "Priority must be between 1 and 4." );
        }
    }

    private static void validateTitle( final String title ) {
        if ( title == null || title.isBlank() ) {
            throw new WorkValidationException( "title", "A title is required." );
        }
        if ( title.length() > MAX_TITLE ) {
            throw new WorkValidationException( "title", "A title may be at most " + MAX_TITLE + " characters." );
        }
    }

    private static void validateShape( final Item next, final Item newParent ) {
        if ( !WorkStatus.allowedFor( next.level() ).contains( next.status() ) ) {
            throw new WorkValidationException( "status",
                "A " + next.level().wire() + " cannot have status " + next.status().wire() + "." );
        }
        if ( next.level() == WorkLevel.EPIC && next.productId() != null ) {
            throw new WorkValidationException( "productKey", "An epic has no product of its own." );
        }
        if ( newParent != null && !newParent.level().canParent( next.level() ) ) {
            throw new WorkValidationException( "parentKey",
                "A " + newParent.level().wire() + " cannot be the parent of a " + next.level().wire() + "." );
        }
    }

    private void checkAssignee( final Project project, final String before, final String after )
            throws SQLException {
        if ( after != null && !after.equals( before ) && !members.isMember( project.id(), after ) ) {
            throw new WorkValidationException( "assignee", "The assignee is not a member of this project." );
        }
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
