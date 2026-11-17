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

import com.wikantik.work.WorkScopes.ItemScope;
import com.wikantik.work.WorkScopes.Scope;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Change records: a branch of work against a product, attached to an item, from open to merged. Reached through
 * {@link WorkService#changes()}. A change is as visible as its item: unseen and unknown are one answer. The gates
 * ({@link WorkGates}) are decided on the change row as it is under lock, against the item's effective policy as it
 * is at this call.
 */
public final class WorkChangesService {

    /** How many changes of an item are open or in review, and how many of those are in review. */
    public record ChangeCounts( int live, int inReview ) {
    }

    /** A change with the keys a reader needs: its item's and its product's. */
    public record ChangeView( Change change, String itemKey, String productKey ) {
    }

    private final WorkStore store;
    private final WorkScopes scopes;

    WorkChangesService( final WorkStore store, final WorkScopes scopes ) {
        this.store = store;
        this.scopes = scopes;
    }

    public List< Change > list( final Actor a, final String itemKey ) throws SQLException {
        final ItemScope is = scopes.itemScope( a, itemKey );
        is.scope().require( Action.VIEW, is.item() );
        return store.changes().forItem( is.item().id() );
    }

    /** The item's changes, oldest first, each with its item and product key. */
    public List< ChangeView > views( final Actor a, final String itemKey ) throws SQLException {
        final ItemScope is = scopes.itemScope( a, itemKey );
        is.scope().require( Action.VIEW, is.item() );
        final Map< Long, String > products = productKeys();
        return store.changes().forItem( is.item().id() ).stream()
            .map( c -> new ChangeView( c, is.item().key(), products.get( c.productId() ) ) ).toList();
    }

    /** One change with its keys; unknown and unseen are the same "not found". */
    public ChangeView view( final Actor a, final long changeId ) throws SQLException {
        final ItemScope is = scopeOf( a, changeId );
        is.scope().require( Action.VIEW, is.item() );
        final Change c = store.changes().change( changeId )
            .orElseThrow( () -> new WorkNotFoundException( WorkScopes.NOT_FOUND ) );
        return new ChangeView( c, is.item().key(), productKeys().get( c.productId() ) );
    }

    private Map< Long, String > productKeys() throws SQLException {
        final Map< Long, String > keys = new LinkedHashMap<>();
        for ( final Product p : store.products() ) {
            keys.put( p.id(), p.key() );
        }
        return keys;
    }

    /** Live-change counts by item key for the items of a project that have any. */
    public Map< String, ChangeCounts > counts( final Actor a, final String projectKey ) throws SQLException {
        final Scope s = scopes.scope( a, projectKey );
        s.require( Action.VIEW, null );
        final Map< Long, WorkChangeStore.Counts > byId = store.changes().counts( s.project().id() );
        final Map< String, ChangeCounts > out = new LinkedHashMap<>();
        for ( final Map.Entry< Long, WorkChangeStore.Counts > e : byId.entrySet() ) {
            final Item item = store.itemById( e.getKey() ).orElse( null );
            if ( item != null ) {
                out.put( item.key(), new ChangeCounts( e.getValue().live(), e.getValue().inReview() ) );
            }
        }
        return out;
    }

    /**
     * Opens a change on the item, against the product the item targets. With a merged commit in the draft the
     * work is already on the branch: the change is recorded as merged directly, which an agent may do only where a
     * lead allowed direct commits.
     */
    public Change open( final Actor a, final String itemKey, final ChangeDraft d ) throws SQLException {
        final ItemScope is = writable( scopes.itemScope( a, itemKey ), Action.OPEN_CHANGE );
        final PolicyChain chain = store.policyChain( is.item().id() );
        final Product product = chain.productOrNull();
        if ( product == null ) {
            throw new WorkValidationException( "productKey", "Set a product on this item before opening a change." );
        }
        final String merged = ChangeInputs.sha( "mergedSha", d.mergedSha() );
        if ( merged != null ) {
            scopes.decide( is.scope(), "direct", Action.OPEN_CHANGE, WorkGates.mayOpenDirect( a, WorkAutonomy.resolve( chain ) ) );
        }
        final String base = d.baseBranch() == null || d.baseBranch().isBlank() ? product.defaultBranch()
            : d.baseBranch();
        final ChangeDraft clean = new ChangeDraft( ChangeInputs.branch( "branch", d.branch() ),
            ChangeInputs.branch( "baseBranch", base ), ChangeInputs.sha( "headSha", d.headSha() ),
            ChangeInputs.url( d.reviewUrl() ), merged );
        return store.changes().open( is.item(), product.id(), clean, a );
    }

    public Change update( final Actor a, final long changeId, final String headSha, final String reviewUrl )
            throws SQLException {
        final ItemScope is = writable( scopeOf( a, changeId ), Action.OPEN_CHANGE );
        return store.changes().update( is.item(), changeId, ChangeInputs.sha( "headSha", headSha ),
            ChangeInputs.url( reviewUrl ), locked -> { }, a );
    }

    public Change requestReview( final Actor a, final long changeId ) throws SQLException {
        final ItemScope is = writable( scopeOf( a, changeId ), Action.OPEN_CHANGE );
        return store.changes().requestReview( is.item(), changeId, locked -> { }, a );
    }

    /** Approves the change at its current commit, or sends it back with a note. */
    public Change review( final Actor a, final long changeId, final boolean approved, final String note )
            throws SQLException {
        final ItemScope is = writable( scopeOf( a, changeId ), Action.REVIEW_CHANGE );
        return store.changes().review( is.item(), changeId, approved, ChangeInputs.note( note ), locked -> {
            scopes.decide( is.scope(), "review", Action.REVIEW_CHANGE, WorkGates.mayReview( a, locked ) );
            if ( approved && locked.headSha() == null ) {
                throw new WorkValidationException( "headSha", "This change names no commit to approve." );
            }
        }, a );
    }

    /**
     * Reports the change merged at {@code mergedSha}. An agent must be the one whose approval stands on the
     * change's current commit, and the commit it reports must be that one; a person may merge any live change.
     */
    public Change merge( final Actor a, final long changeId, final String mergedSha ) throws SQLException {
        final ItemScope is = writable( scopeOf( a, changeId ), Action.MERGE_CHANGE );
        final String sha = ChangeInputs.requiredSha( "mergedSha", mergedSha );
        final EffectivePolicy policy = WorkAutonomy.resolve( store.policyChain( is.item().id() ) );
        return store.changes().merge( is.item(), changeId, sha, locked -> {
            scopes.decide( is.scope(), "merge", Action.MERGE_CHANGE, WorkGates.mayMerge( a, locked, policy ) );
            final boolean reviewed = locked.approvedForHead() && sha.equals( locked.headSha() );
            if ( a.kind() == Actor.Kind.AGENT && !reviewed ) {
                throw new WorkConflictException( "This change has no approval for its current commit.",
                    WorkConflictException.NOT_APPROVED );
            }
            return reviewed ? MergeKind.REVIEWED : MergeKind.UNREVIEWED;
        }, a );
    }

    public Change abandon( final Actor a, final long changeId, final String note ) throws SQLException {
        final ItemScope is = writable( scopeOf( a, changeId ), Action.OPEN_CHANGE );
        return store.changes().abandon( is.item(), changeId, ChangeInputs.note( note ), locked -> { }, a );
    }

    /** The scope of a change's item; an unknown change and one on an unseen item are the same "not found". */
    private ItemScope scopeOf( final Actor a, final long changeId ) throws SQLException {
        final Change c = store.changes().change( changeId ).orElseThrow( () -> new WorkNotFoundException( WorkScopes.NOT_FOUND ) );
        return scopes.itemScopeById( a, c.itemId() );
    }

    private static ItemScope writable( final ItemScope is, final Action action ) {
        is.scope().require( action, is.item() );
        is.scope().requireWritable();
        return is;
    }
}
