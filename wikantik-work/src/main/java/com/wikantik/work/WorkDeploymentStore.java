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
import java.util.List;
import java.util.Optional;

/**
 * Deployments. Reached through {@link WorkStore#deployments()}. A request and every move run in one transaction
 * with the history row and the events on the carried items; a move that the state does not allow is a
 * {@link WorkConflictException} ({@link WorkConflictException#BAD_TRANSITION}), decided on the locked row.
 */
public interface WorkDeploymentStore {

    /** Records the request: the deployment, the items it carries, its first history row and an event per item. */
    Deployment request( Product product, String environment, String version, List< Item > items,
                        Long remediatesId, Actor by ) throws SQLException;

    /** A request that remediates nothing. */
    default Deployment request( final Product product, final String environment, final String version,
                                final List< Item > items, final Actor by ) throws SQLException {
        return request( product, environment, version, items, null, by );
    }

    Optional< Deployment > deployment( long id ) throws SQLException;

    /** The product's deployments, most recently requested first. */
    List< Deployment > forProduct( long productId, int limit, int offset ) throws SQLException;

    /** Every deployment that carries the item, most recently requested first. */
    List< Deployment > carrying( long itemId ) throws SQLException;

    /**
     * Moves the deployment to {@code target}. {@code evidence} is stored with a move to verified; {@code note}
     * goes into the history. A move to verified writes a {@code shipped} event on every carried item, a move to
     * rolled back a {@code rolled_back} event.
     */
    Deployment move( long id, DeploymentState target, String evidence, String note, Actor by ) throws SQLException;

    /** The deployment's history, oldest first. */
    List< DeploymentEvent > history( long id ) throws SQLException;

    /**
     * Whether the item has shipped: a deployment to production that carries it is verified (and not rolled back
     * since), and was verified after the item's last merged change.
     */
    boolean shippedVerified( long itemId ) throws SQLException;

    /** Every deployment still waiting for approval, oldest first. */
    List< Deployment > awaitingApproval() throws SQLException;
}
