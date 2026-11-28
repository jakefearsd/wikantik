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
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** The project, membership and product half of {@link WorkStore}; split out to keep each interface small. */
public interface WorkCatalogStore {

    // ---- projects

    /** Creates a project with no members; the key must match {@code ^[A-Z][A-Z0-9]{1,9}$} and be unused. */
    default Project createProject( final String key, final String name, final String description )
            throws SQLException {
        return createProject( key, name, description, null );
    }

    /**
     * Creates a project as {@link #createProject(String, String, String)} does and, when {@code creator} is a person
     * with a login, makes them its first lead with one {@code member} event, in the same transaction: the creator's
     * keys (which never act as an admin) and later non-admin sessions see the project at once. A {@code null} creator,
     * or one that is not a person, adds nobody.
     */
    Project createProject( String key, String name, String description, Actor creator ) throws SQLException;

    Optional< Project > project( String key ) throws SQLException;

    /** Every project, archived included. Visibility filtering is {@link #visibleProjects}. */
    List< Project > projects() throws SQLException;

    /**
     * Changes the project's settings and writes one {@code settings} event per changed field (field name in the note),
     * in one transaction; nothing changed writes nothing.
     */
    void updateProject( long id, String name, String description, Autonomy defaultAutonomy, Actor by ) throws SQLException;

    /**
     * Archives the project with a {@code settings} event noted {@code archived} and releases every claim on its items
     * ({@code released}, note {@code project archived}, actor {@code by}), in one transaction: nobody could renew or
     * release a claim in an archived project.
     *
     * @return how many claims that released; archiving an archived project releases none
     */
    int archiveProject( long id, Instant at, Actor by ) throws SQLException;

    // ---- membership

    /**
     * Adds the member or changes the role of an existing one, with a {@code member} event whose values are
     * {@code kind:principal:role} (from null on add), in one transaction. The same role again writes nothing.
     * Demoting an agent to viewer releases its claims in the project in the same transaction.
     *
     * @return how many claims that released (0 unless an agent was demoted)
     */
    int putMember( long projectId, String principal, String principalKind, WorkRole role, Actor by ) throws SQLException;

    /**
     * {@link #putMember(long, String, String, WorkRole, Actor)}, and in the same transaction, after the change, each
     * listed sponsor's role in the project is read again; where it is now below {@code member} (and the sponsor is
     * not a wiki admin) the claims held in the project by the agents they sponsor are released with the reason
     * {@code sponsor access removed}. The count returned includes those.
     */
    int putMember( long projectId, String principal, String principalKind, WorkRole role, Actor by,
                   java.util.Collection< Actor > sponsorsToRecheck ) throws SQLException;

    /**
     * Deletes the grant with a {@code member} event (to null), in one transaction; no grant, nothing written. An
     * agent's claims in the project are released in the same transaction.
     *
     * @return how many claims that released
     */
    int removeMember( long projectId, String principal, String principalKind, Actor by )
        throws SQLException;

    /** {@link #removeMember(long, String, String, Actor)} with the sponsor recheck of {@link #putMember}. */
    int removeMember( long projectId, String principal, String principalKind, Actor by,
                      java.util.Collection< Actor > sponsorsToRecheck ) throws SQLException;

    List< Member > members( long projectId ) throws SQLException;

    /**
     * The highest role the actor holds, raw: its {@code user} row, any {@code group} row for one of its groups,
     * and, for an agent, the {@code agent} row whose principal is its agent id. Empty when none; a Wiki
     * Admin is not given a role here, see {@link #visibleProjects}. It may be {@link WorkRole#REPORTER}, which is no
     * standing: only {@code WorkStanding} asks this, and it turns the answer into standing (viewer or above).
     */
    Optional< WorkRole > roleFor( long projectId, Actor actor ) throws SQLException;

    /**
     * All projects for an admin, otherwise those where the actor holds a role that gives standing
     * ({@link WorkRole#STANDING}: viewer, member or lead; a reporter row does not count). Only {@code WorkStanding}
     * asks this.
     */
    List< Project > visibleProjects( Actor actor ) throws SQLException;

    // ---- products

    Product createProduct( Product draft ) throws SQLException;

    Optional< Product > product( String key ) throws SQLException;

    List< Product > products() throws SQLException;

    /** Applies {@code after} to the row of {@code before} and records one event per changed field, in one transaction. */
    void updateProduct( Product before, Product after, Actor by ) throws SQLException;

    /**
     * Sets {@code archived_at} and records an {@code archived} event, in one transaction; the product also stops
     * being any project's default (one {@code default_product} settings event per such project).
     */
    void archiveProduct( Product product, Instant at, Actor by ) throws SQLException;

    /** The product's recorded changes, newest first (ties by id), at most {@code limit}. */
    List< ProductEvent > productEvents( long productId, int limit ) throws SQLException;
}
