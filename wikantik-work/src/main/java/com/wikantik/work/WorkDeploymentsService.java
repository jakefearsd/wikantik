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

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deployments: a product version going to an environment, carrying items, from requested to verified. Reached
 * through {@link WorkService#deployments()}. A deployment is seen through the items it carries: a caller who can
 * see none of them gets "not found", and one who can see some is shown those and nothing of the rest, not even
 * how many there are. The product's deploy gate decides who approves ({@link DeployGates}); autonomy never does.
 */
public final class WorkDeploymentsService {

    /**
     * A deployment as one caller sees it. {@code deployment.itemIds()} holds only the items in
     * {@code visibleItems}; {@code mayApprove} and {@code mayRun} say what this caller could do next.
     */
    public record DeploymentView( Deployment deployment, String productKey, List< Item > visibleItems,
                                  boolean mayApprove, boolean mayRun ) {
    }

    /** Work nobody has approved, or that was dropped, is not something a deployment ships. */
    private static final java.util.Set< WorkStatus > NOT_SHIPPABLE = java.util.EnumSet.of( WorkStatus.DRAFT,
        WorkStatus.REFINING, WorkStatus.CANCELLED );
    static final String REJECT =
        "Rejecting this deployment takes whoever requested it, or someone who may approve it.";
    static final String RUN = "Running this deployment takes membership of every project it carries.";
    static final int MAX_EVIDENCE = DeploymentInputs.MAX_EVIDENCE;
    static final int MAX_NOTE = DeploymentInputs.MAX_NOTE;
    /** How many of a product's deployments one listing reads at a time, and at most, looking for visible ones. */
    private static final int PAGE = 100;
    private static final int MAX_SCANNED = 1_000;

    /** A deployment the caller can see some of, with what they can see and do of it: one read of its items. */
    private record Seen( Deployment deployment, DeploymentAccess access ) {
    }

    private final WorkStore store;
    private final WorkScopes scopes;

    WorkDeploymentsService( final WorkStore store, final WorkScopes scopes ) {
        this.store = store;
        this.scopes = scopes;
    }

    /**
     * Requests a deployment of a product version carrying the named items. The caller must be a member of every
     * item's project, and every item must target the product.
     */
    public DeploymentView request( final Actor a, final DeploymentDraft d ) throws SQLException {
        final Product product = visibleProduct( a, d.productKey() );
        final String version = DeploymentInputs.version( d.version() );
        final String environment = DeploymentInputs.environment( d.environment() );
        final Map< Long, Item > items = new LinkedHashMap<>();
        for ( final String key : d.itemKeys() == null ? List.< String >of() : d.itemKeys() ) {
            final ItemScope is = scopes.itemScope( a, key );
            is.scope().require( Action.REQUEST_DEPLOYMENT, is.item() );
            is.scope().requireWritable();
            if ( is.item().productId() == null || is.item().productId() != product.id() ) {
                throw new WorkValidationException( "items", is.item().key() + " does not target this product." );
            }
            if ( NOT_SHIPPABLE.contains( is.item().status() ) ) {
                throw new WorkValidationException( "items", is.item().key() + " is " + is.item().status().wire()
                    + "; a deployment carries work that is ready or later." );
            }
            items.put( is.item().id(), is.item() );
        }
        if ( items.isEmpty() ) {
            throw new WorkValidationException( "items", "A deployment carries at least one item." );
        }
        if ( d.remediatesId() != null ) {
            Remediation.check( seen( a, d.remediatesId() ).deployment(), product, environment );
        }
        final Deployment requested = store.deployments().request( product, environment, version,
            new ArrayList<>( items.values() ), d.remediatesId(), a );
        scopes.deploymentReached( DeploymentState.REQUESTED );
        return view( a, requested );
    }

    public DeploymentView deployment( final Actor a, final long id ) throws SQLException {
        return view( a, seen( a, id ) );
    }

    private DeploymentView view( final Actor a, final Seen seen ) throws SQLException {
        return view( a, seen.deployment(), seen.access() );
    }

    /** The product's deployments the caller can see any part of, most recently requested first. */
    public List< DeploymentView > forProduct( final Actor a, final String productKey, final int limit )
            throws SQLException {
        final Product product = visibleProduct( a, productKey );
        final int wanted = Math.max( 1, Math.min( limit, 100 ) );
        final List< DeploymentView > out = new ArrayList<>();
        // The limit counts what this caller sees: hidden deployments are passed over, a page at a time.
        for ( int offset = 0; out.size() < wanted && offset < MAX_SCANNED; offset += PAGE ) {
            final List< Deployment > page = store.deployments().forProduct( product.id(), PAGE, offset );
            for ( final Deployment d : page ) {
                final DeploymentAccess access = access( a, d );
                if ( access.seesAny() && out.size() < wanted ) {
                    out.add( view( a, d, access ) );
                }
            }
            if ( page.size() < PAGE ) {
                break;
            }
        }
        return out;
    }

    public List< DeploymentEvent > history( final Actor a, final long id ) throws SQLException {
        return store.deployments().history( seen( a, id ).deployment().id() );
    }

    /** Approves the deployment, if the product's gate lets this caller. */
    public DeploymentView approve( final Actor a, final long id, final String note ) throws SQLException {
        final Seen seen = seen( a, id );
        final Decision decision = mayApprove( a, seen.deployment(), seen.access() );
        scopes.gateDecided( a, "deploy", decision.allowed() );
        if ( !decision.allowed() ) {
            scopes.denied( Action.APPROVE_DEPLOYMENT );
            throw new WorkDeniedException( decision.reason() );
        }
        return view( a, moved( store.deployments().move( id, DeploymentState.APPROVED, null, DeploymentInputs.note( note ), a ) ) );
    }

    /**
     * Withdraws or turns down a deployment that has not started running: whoever requested it may, and so may
     * anyone who could approve it. A rejected deployment never runs and is never cleared for the deploy script.
     */
    public DeploymentView reject( final Actor a, final long id, final String note ) throws SQLException {
        final Seen seen = seen( a, id );
        final Deployment d = seen.deployment();
        final boolean requester = a.kind() == Actor.Kind.AGENT ? a.agentId().equals( d.requestedByAgentId() )
            : d.requestedByAgentId() == null && a.login().equals( d.requestedBy() );
        if ( !requester && !mayApprove( a, d, seen.access() ).allowed() ) {
            scopes.denied( Action.APPROVE_DEPLOYMENT );
            throw new WorkDeniedException( REJECT );
        }
        return view( a, moved( store.deployments().move( id, DeploymentState.REJECTED, null, DeploymentInputs.note( note ), a ) ) );
    }

    public DeploymentView start( final Actor a, final long id ) throws SQLException {
        return move( a, id, DeploymentState.RUNNING, null, null );
    }

    /** Marks the deployment verified; the health and smoke-test output must be attached. */
    public DeploymentView verify( final Actor a, final long id, final String evidence ) throws SQLException {
        seen( a, id );
        return move( a, id, DeploymentState.VERIFIED, DeploymentInputs.evidence( evidence ), null );
    }

    public DeploymentView fail( final Actor a, final long id, final String note ) throws SQLException {
        return move( a, id, DeploymentState.FAILED, null, note );
    }

    public DeploymentView rollBack( final Actor a, final long id, final String note ) throws SQLException {
        return move( a, id, DeploymentState.ROLLED_BACK, null, note );
    }

    /**
     * Whether a deploy script may go ahead with {@code version}: the deployment is that version and is approved
     * or already running. Anything else is no.
     */
    public boolean cleared( final Actor a, final long id, final String version ) throws SQLException {
        return gate( a, id, version ).approved();
    }

    /** The answer to a deploy script, from one read: whether to go ahead, and what the deployment is. */
    public record GateAnswer( boolean approved, DeploymentState state, String version, String environment ) {
    }

    /** {@link #cleared} with the state, version and environment the answer was taken from. */
    public GateAnswer gate( final Actor a, final long id, final String version ) throws SQLException {
        final Deployment d = seen( a, id ).deployment();
        return new GateAnswer( d.state().clearedToRun() && d.version().equals( version ), d.state(), d.version(),
            d.environment() );
    }

    /** The verified deployments to production that carried the item, most recent first: what it shipped in. */
    public List< DeploymentView > shippedIn( final Actor a, final String itemKey ) throws SQLException {
        final ItemScope is = scopes.itemScope( a, itemKey );
        is.scope().require( Action.VIEW, is.item() );
        final List< DeploymentView > out = new ArrayList<>();
        for ( final Deployment d : store.deployments().carrying( is.item().id() ) ) {
            if ( d.state() == DeploymentState.VERIFIED && JdbcWorkDeployments.PRODUCTION.equals( d.environment() )
                    && is.item().productId() != null && is.item().productId() == d.productId() ) {
                out.add( view( a, d, access( a, d ) ) );
            }
        }
        return out;
    }

    /** The deployments waiting for an approval this caller could give, oldest first. */
    public List< DeploymentView > awaitingMyApproval( final Actor a ) throws SQLException {
        final List< DeploymentView > out = new ArrayList<>();
        for ( final Deployment d : store.deployments().awaitingApproval() ) {
            final DeploymentAccess access = access( a, d );
            if ( access.seesAny() && mayApprove( a, d, access ).allowed() ) {
                out.add( view( a, d, access ) );
            }
        }
        return out;
    }

    private DeploymentView move( final Actor a, final long id, final DeploymentState target, final String evidence,
                                 final String note ) throws SQLException {
        if ( !seen( a, id ).access().memberOfEvery() && !a.instanceAdmin() ) {
            scopes.denied( Action.RUN_DEPLOYMENT );
            throw new WorkDeniedException( RUN );
        }
        return view( a, moved( store.deployments().move( id, target, evidence, DeploymentInputs.note( note ), a ) ) );
    }

    /** Reports the state a deployment has just reached, once the move is committed. */
    private Deployment moved( final Deployment d ) {
        scopes.deploymentReached( d.state() );
        return d;
    }

    private Decision mayApprove( final Actor a, final Deployment d, final DeploymentAccess access )
            throws SQLException {
        return DeployGates.mayApprove( a, productOf( d ).deployGate(), access.leadOfEvery(), access.memberOfEvery() );
    }

    /** The deployment, or "not found" when it does not exist or the caller can see none of what it carries. */
    private Seen seen( final Actor a, final long id ) throws SQLException {
        final Deployment d = store.deployments().deployment( id )
            .orElseThrow( () -> new WorkNotFoundException( WorkScopes.NOT_FOUND ) );
        final DeploymentAccess access = access( a, d );
        if ( !access.seesAny() ) {
            throw new WorkNotFoundException( WorkScopes.NOT_FOUND );
        }
        return new Seen( d, access );
    }

    private DeploymentAccess access( final Actor a, final Deployment d ) throws SQLException {
        return DeploymentAccess.of( store, scopes.standing(), a, d.itemIds() );
    }

    /** A deployment that was just written, as the caller sees it now. */
    private DeploymentView view( final Actor a, final Deployment d ) throws SQLException {
        return view( a, d, access( a, d ) );
    }

    private DeploymentView view( final Actor a, final Deployment d, final DeploymentAccess access )
            throws SQLException {
        final List< Item > visible = access.visibleItems();
        final Deployment shown = new Deployment( d.id(), d.productId(), d.environment(), d.version(), d.state(),
            d.requestedBy(), d.requestedByAgentId(), d.approvedBy(), d.approvedByAgentId(), d.requestedAt(),
            d.approvedAt(), d.startedAt(), d.verifiedAt(), d.closedAt(), d.evidence(),
            visibleRemediates( a, d ), visible.stream().map( Item::id ).toList() );
        return new DeploymentView( shown, productOf( d ).key(), visible,
            d.state() == DeploymentState.REQUESTED && mayApprove( a, d, access ).allowed(),
            access.memberOfEvery() || a.instanceAdmin() );
    }

    /** The id of the deployment this one fixes, only when the caller may see that deployment; else null. */
    private Long visibleRemediates( final Actor a, final Deployment d ) throws SQLException {
        if ( d.remediatesId() == null ) {
            return null;
        }
        final java.util.Optional< Deployment > target = store.deployments().deployment( d.remediatesId() );
        return target.isPresent() && access( a, target.get() ).seesAny() ? d.remediatesId() : null;
    }

    private Product productOf( final Deployment d ) throws SQLException {
        for ( final Product p : store.products() ) {
            if ( p.id() == d.productId() ) {
                return p;
            }
        }
        throw new SQLException( "work_product " + d.productId() + " not found" );
    }

    /** A product the caller may name: anyone in at least one project may; everyone else learns nothing. */
    private Product visibleProduct( final Actor a, final String key ) throws SQLException {
        if ( !a.instanceAdmin() && scopes.standing().visibleProjects( a ).isEmpty() ) {
            throw new WorkNotFoundException( WorkScopes.NOT_FOUND );
        }
        return store.product( key == null ? "" : key.trim() ).filter( p -> p.archivedAt() == null )
            .orElseThrow( () -> new WorkNotFoundException( WorkScopes.NOT_FOUND ) );
    }
}
