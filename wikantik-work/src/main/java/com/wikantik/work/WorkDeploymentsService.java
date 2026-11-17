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
import java.util.regex.Pattern;

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

    static final String RUN = "Running this deployment takes membership of every project it carries.";
    static final int MAX_EVIDENCE = 20_000;
    static final int MAX_NOTE = 2_000;
    private static final Pattern VERSION = Pattern.compile( "[A-Za-z0-9._+-]{1,60}" );
    private static final Pattern ENVIRONMENT = Pattern.compile( "[a-z0-9-]{1,40}" );
    private static final String PRODUCTION = "production";

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
        final String version = text( "version", d.version(), VERSION,
            "A version is 1 to 60 letters, digits, dots, plus signs, hyphens or underscores." );
        final String environment = d.environment() == null || d.environment().isBlank() ? PRODUCTION
            : text( "environment", d.environment(), ENVIRONMENT,
                "An environment is 1 to 40 lower-case letters, digits or hyphens." );
        final Map< Long, Item > items = new LinkedHashMap<>();
        for ( final String key : d.itemKeys() == null ? List.< String >of() : d.itemKeys() ) {
            final ItemScope is = scopes.itemScope( a, key );
            is.scope().require( Action.REQUEST_DEPLOYMENT, is.item() );
            is.scope().requireWritable();
            if ( is.item().productId() == null || is.item().productId() != product.id() ) {
                throw new WorkValidationException( "items", is.item().key() + " does not target this product." );
            }
            items.put( is.item().id(), is.item() );
        }
        if ( items.isEmpty() ) {
            throw new WorkValidationException( "items", "A deployment carries at least one item." );
        }
        return view( a, store.deployments().request( product, environment, version,
            new ArrayList<>( items.values() ), a ) );
    }

    public DeploymentView deployment( final Actor a, final long id ) throws SQLException {
        return view( a, seen( a, id ) );
    }

    /** The product's deployments the caller can see any part of, most recently requested first. */
    public List< DeploymentView > forProduct( final Actor a, final String productKey, final int limit )
            throws SQLException {
        final Product product = visibleProduct( a, productKey );
        final List< DeploymentView > out = new ArrayList<>();
        for ( final Deployment d : store.deployments().forProduct( product.id(), Math.max( 1, Math.min( limit, 100 ) ) ) ) {
            if ( access( a, d ).seesAny() ) {
                out.add( view( a, d ) );
            }
        }
        return out;
    }

    public List< DeploymentEvent > history( final Actor a, final long id ) throws SQLException {
        return store.deployments().history( seen( a, id ).id() );
    }

    /** Approves the deployment, if the product's gate lets this caller. */
    public DeploymentView approve( final Actor a, final long id, final String note ) throws SQLException {
        final Deployment d = seen( a, id );
        final Decision decision = mayApprove( a, d, access( a, d ) );
        scopes.gateDecided( a, "deploy", decision.allowed() );
        if ( !decision.allowed() ) {
            scopes.denied( Action.APPROVE_DEPLOYMENT );
            throw new WorkDeniedException( decision.reason() );
        }
        return view( a, store.deployments().move( id, DeploymentState.APPROVED, null, note( note ), a ) );
    }

    public DeploymentView start( final Actor a, final long id ) throws SQLException {
        return move( a, id, DeploymentState.RUNNING, null, null );
    }

    /** Marks the deployment verified; the health and smoke-test output must be attached. */
    public DeploymentView verify( final Actor a, final long id, final String evidence ) throws SQLException {
        seen( a, id );
        if ( evidence == null || evidence.isBlank() ) {
            throw new WorkValidationException( "evidence",
                "Attach the health and smoke-test output before marking this verified." );
        }
        if ( evidence.length() > MAX_EVIDENCE ) {
            throw new WorkValidationException( "evidence",
                "Evidence may be at most " + MAX_EVIDENCE + " characters." );
        }
        return move( a, id, DeploymentState.VERIFIED, evidence, null );
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
        final Deployment d = seen( a, id );
        return d.state().clearedToRun() && d.version().equals( version );
    }

    /** The deployments waiting for an approval this caller could give, oldest first. */
    public List< DeploymentView > awaitingMyApproval( final Actor a ) throws SQLException {
        final List< DeploymentView > out = new ArrayList<>();
        for ( final Deployment d : store.deployments().awaitingApproval() ) {
            final DeploymentAccess access = access( a, d );
            if ( access.seesAny() && mayApprove( a, d, access ).allowed() ) {
                out.add( view( a, d ) );
            }
        }
        return out;
    }

    private DeploymentView move( final Actor a, final long id, final DeploymentState target, final String evidence,
                                 final String note ) throws SQLException {
        final Deployment d = seen( a, id );
        if ( !access( a, d ).memberOfEvery() && !a.instanceAdmin() ) {
            scopes.denied( Action.RUN_DEPLOYMENT );
            throw new WorkDeniedException( RUN );
        }
        return view( a, store.deployments().move( id, target, evidence, note( note ), a ) );
    }

    private Decision mayApprove( final Actor a, final Deployment d, final DeploymentAccess access )
            throws SQLException {
        return DeployGates.mayApprove( a, productOf( d ).deployGate(), access.leadOfEvery(), access.memberOfEvery() );
    }

    /** The deployment, or "not found" when it does not exist or the caller can see none of what it carries. */
    private Deployment seen( final Actor a, final long id ) throws SQLException {
        final Deployment d = store.deployments().deployment( id )
            .orElseThrow( () -> new WorkNotFoundException( WorkScopes.NOT_FOUND ) );
        if ( !access( a, d ).seesAny() ) {
            throw new WorkNotFoundException( WorkScopes.NOT_FOUND );
        }
        return d;
    }

    private DeploymentAccess access( final Actor a, final Deployment d ) throws SQLException {
        return DeploymentAccess.of( store, scopes.standing(), a, d.itemIds() );
    }

    private DeploymentView view( final Actor a, final Deployment d ) throws SQLException {
        final DeploymentAccess access = access( a, d );
        final List< Item > visible = access.visibleItems();
        final Deployment shown = new Deployment( d.id(), d.productId(), d.environment(), d.version(), d.state(),
            d.requestedBy(), d.requestedByAgentId(), d.approvedBy(), d.approvedByAgentId(), d.requestedAt(),
            d.approvedAt(), d.startedAt(), d.verifiedAt(), d.closedAt(), d.evidence(),
            visible.stream().map( Item::id ).toList() );
        return new DeploymentView( shown, productOf( d ).key(), visible,
            d.state() == DeploymentState.REQUESTED && mayApprove( a, d, access ).allowed(),
            access.memberOfEvery() || a.instanceAdmin() );
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

    private static String text( final String field, final String value, final Pattern pattern, final String rule ) {
        final String trimmed = value == null ? "" : value.trim();
        if ( !pattern.matcher( trimmed ).matches() ) {
            throw new WorkValidationException( field, rule );
        }
        return trimmed;
    }

    private static String note( final String note ) {
        if ( note == null || note.isBlank() ) {
            return null;
        }
        if ( note.length() > MAX_NOTE ) {
            throw new WorkValidationException( "note", "A note may be at most " + MAX_NOTE + " characters." );
        }
        return note;
    }
}
