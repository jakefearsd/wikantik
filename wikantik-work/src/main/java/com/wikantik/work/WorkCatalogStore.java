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

    /** Creates a project; the key must match {@code ^[A-Z][A-Z0-9]{1,9}$} and be unused. */
    Project createProject( String key, String name, String description ) throws SQLException;

    Optional< Project > project( String key ) throws SQLException;

    /** Every project, archived included. Visibility filtering is {@link #visibleProjects}. */
    List< Project > projects() throws SQLException;

    /**
     * Changes the project's settings and writes one {@code settings} event per changed field (field name in the note),
     * in one transaction; nothing changed writes nothing.
     */
    void updateProject( long id, String name, String description, Autonomy defaultAutonomy, Actor by ) throws SQLException;

    /** Archives the project with a {@code settings} event noted {@code archived}, in one transaction. */
    void archiveProject( long id, Instant at, Actor by ) throws SQLException;

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
     * Deletes the grant with a {@code member} event (to null), in one transaction; no grant, nothing written. An
     * agent's claims in the project are released in the same transaction.
     *
     * @return how many claims that released
     */
    int removeMember( long projectId, String principal, String principalKind, Actor by )
        throws SQLException;

    List< Member > members( long projectId ) throws SQLException;

    /**
     * The highest role the actor holds: its {@code user} row, any {@code group} row for one of its groups,
     * and, for an agent, the {@code agent} row whose principal is its agent id. Empty when none; a Wiki
     * Admin is not given a role here, see {@link #visibleProjects}.
     */
    Optional< WorkRole > roleFor( long projectId, Actor actor ) throws SQLException;

    /** All projects for an admin, otherwise those where the actor has a role. */
    List< Project > visibleProjects( Actor actor ) throws SQLException;

    // ---- products

    Product createProduct( Product draft ) throws SQLException;

    Optional< Product > product( String key ) throws SQLException;

    List< Product > products() throws SQLException;

    void updateProduct( Product p ) throws SQLException;

    void archiveProduct( long id, Instant at ) throws SQLException;
}
