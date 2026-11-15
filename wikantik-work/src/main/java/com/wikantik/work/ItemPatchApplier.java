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
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;

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
        final String assignee = blankToNull( d.assignee() );
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
            final String key = string( "parentKey", changes.get( "parentKey" ) );
            newParent = key == null ? null : resolveParent( project, key, current.id() );
            parentId = newParent == null ? null : newParent.id();
        }
        Long productId = current.productId();
        if ( changes.containsKey( "productKey" ) ) {
            final String key = string( "productKey", changes.get( "productKey" ) );
            productId = key == null ? null : resolveProduct( key ).id();
        }
        final WorkStatus status = changes.containsKey( "status" ) ? status( changes.get( "status" ) )
            : current.status();
        final String assignee = changes.containsKey( "assignee" )
            ? blankToNull( string( "assignee", changes.get( "assignee" ) ) ) : current.assignee();
        final double rank = changes.containsKey( "rank" ) ? number( "rank", changes.get( "rank" ) )
            : status != current.status() ? store.maxRank( project.id(), status ) + RANK_STEP : current.rank();
        final Item next = new Item( current.id(), current.projectId(), current.key(), current.level(), parentId,
            productId,
            changes.containsKey( "kind" ) ? kind( changes.get( "kind" ) ) : current.kind(),
            changes.containsKey( "title" ) ? string( "title", changes.get( "title" ) ) : current.title(),
            changes.containsKey( "body" ) ? bodyOf( changes.get( "body" ) ) : current.body(),
            status,
            changes.containsKey( "priority" ) ? priority( changes.get( "priority" ) ) : current.priority(),
            rank,
            changes.containsKey( "dueOn" ) ? dueOn( changes.get( "dueOn" ) ) : current.dueOn(),
            assignee, current.reporter(),
            changes.containsKey( "autonomy" ) ? autonomy( changes.get( "autonomy" ) ) : current.autonomy(),
            changes.containsKey( "doneMeans" ) ? doneMeans( changes.get( "doneMeans" ) ) : current.doneMeans(),
            changes.containsKey( "allowDirect" ) ? allowDirect( changes.get( "allowDirect" ) )
                : current.allowDirect(),
            current.version(), current.createdAt(), current.updatedAt(), current.closedAt() );
        validate( next, newParent );
        checkAssignee( project, current.assignee(), assignee );
        if ( status != current.status() && !java.util.Objects.equals( assignee, current.assignee() )
                && ( note == null || note.isBlank() ) ) {
            throw new WorkValidationException( "note", "Changing status and assignee together needs a note." );
        }
        return next;
    }

    /** Field rules; {@code newParent} is the freshly resolved parent, null when the parent is not being set. */
    private static void validate( final Item next, final Item newParent ) {
        if ( next.title() == null || next.title().isBlank() ) {
            throw new WorkValidationException( "title", "A title is required." );
        }
        if ( next.title().length() > MAX_TITLE ) {
            throw new WorkValidationException( "title", "A title may be at most " + MAX_TITLE + " characters." );
        }
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
        if ( next.priority() < 1 || next.priority() > 4 ) {
            throw new WorkValidationException( "priority", "Priority must be between 1 and 4." );
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

    // ---- value coercion: JSON-ish types in, domain types out; every failure names its field

    private static String blankToNull( final String s ) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String string( final String field, final Object v ) {
        if ( v == null ) {
            return null;
        }
        if ( v instanceof String s ) {
            return s;
        }
        throw new WorkValidationException( field, field + " must be text." );
    }

    private static String requiredString( final String field, final Object v ) {
        final String s = string( field, v );
        if ( s == null ) {
            throw new WorkValidationException( field, field + " cannot be cleared." );
        }
        return s;
    }

    private static String bodyOf( final Object v ) {
        final String s = string( "body", v );
        return s == null ? "" : s;
    }

    private static double number( final String field, final Object v ) {
        if ( v instanceof Number n && Double.isFinite( n.doubleValue() ) ) {
            return n.doubleValue();
        }
        throw new WorkValidationException( field, field + " must be a number." );
    }

    private static int priority( final Object v ) {
        final double d = number( "priority", v );
        if ( d != Math.rint( d ) ) {
            throw new WorkValidationException( "priority", "Priority must be a whole number." );
        }
        return d < Integer.MIN_VALUE || d > Integer.MAX_VALUE ? 0 : (int) d;
    }

    private static WorkStatus status( final Object v ) {
        return WorkStatus.fromWire( requiredString( "status", v ) );
    }

    private static WorkKind kind( final Object v ) {
        return WorkKind.fromWire( requiredString( "kind", v ) );
    }

    private static Autonomy autonomy( final Object v ) {
        final String s = string( "autonomy", v );
        return s == null ? null : Autonomy.fromWire( s );
    }

    private static DoneMeans doneMeans( final Object v ) {
        final String s = string( "doneMeans", v );
        return s == null ? null : DoneMeans.fromWire( s );
    }

    private static Boolean allowDirect( final Object v ) {
        if ( v == null || v instanceof Boolean ) {
            return (Boolean) v;
        }
        throw new WorkValidationException( "allowDirect", "allowDirect must be true or false." );
    }

    private static LocalDate dueOn( final Object v ) {
        final String s = string( "dueOn", v );
        if ( s == null || s.isBlank() ) {
            return null;
        }
        try {
            return LocalDate.parse( s.trim() );
        } catch ( final DateTimeException e ) {
            throw new WorkValidationException( "dueOn", "dueOn must be an ISO date such as 2026-11-15." );
        }
    }
}
