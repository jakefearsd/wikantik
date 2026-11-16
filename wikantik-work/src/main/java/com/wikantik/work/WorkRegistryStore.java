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

/** The queue and agent-registry half of {@link WorkStore}. Who may call these is the service's decision. */
public interface WorkRegistryStore {

    // ---- queues

    Queue createQueue( String key, String name, String description ) throws SQLException;

    Optional< Queue > queue( String key ) throws SQLException;

    /** Every queue, retired included, by key. */
    List< Queue > queues() throws SQLException;

    void updateQueue( String key, String name, String description ) throws SQLException;

    void retireQueue( String key, Instant at ) throws SQLException;

    // ---- agents

    /** One transaction: the agent row plus one queue row per queue. The name is stored lowercase. */
    Agent registerAgent( String name, String vendor, String runtime, int apiKeyId, List< String > queues )
        throws SQLException;

    /**
     * One transaction: the agent row with its sponsor, one queue row per queue, and the first sponsor-history row
     * (the sponsor is also who made the change). The name is stored lowercase.
     */
    Agent registerAgent( String name, String vendor, String runtime, List< String > queues, String sponsor )
        throws SQLException;

    /** One change of sponsor as the history keeps it; {@code sponsor} is null while the agent had none. */
    record SponsorChange( String sponsor, java.time.Instant since, String changedBy ) {
    }

    /** One transaction: the agent's sponsor (null for none) and the history row that records the change. */
    void setSponsor( long agentId, String sponsorOrNull, String changedBy ) throws SQLException;

    /** Every sponsor the agent has had, newest first. */
    List< SponsorChange > sponsorHistory( long agentId ) throws SQLException;

    /** The ids of the agents that have a member row in any of the projects. */
    java.util.Set< Long > agentsWithMemberRowIn( java.util.Collection< Long > projectIds ) throws SQLException;

    /** The ids of the projects the agent has a member row in. */
    List< Long > projectsWithAgentMember( long agentId ) throws SQLException;

    Optional< Agent > agent( long id ) throws SQLException;

    /** Case-insensitive. */
    Optional< Agent > agentByName( String name ) throws SQLException;

    /** The agent bound to the key, retired included. */
    Optional< Agent > agentByApiKey( int apiKeyId ) throws SQLException;

    /**
     * The agent the key is bound to now or was bound to before a rebind (retired included); empty for a key that was
     * never an agent's.
     */
    Optional< Agent > agentEverBoundTo( int apiKeyId ) throws SQLException;

    /** Every agent by name, each with its queue keys (ascending), read in one query. */
    List< Agent > agents() throws SQLException;

    /** One transaction: updates the row and replaces the queue rows. */
    void updateAgent( long id, String name, String vendor, String runtime, List< String > queues )
        throws SQLException;

    /**
     * One transaction: records the agent's current key in the key history, then binds the new key. A key in the
     * history never acts as a person again (see {@link #wasEverAgentKey}).
     */
    void rebindAgentKey( long id, int apiKeyId ) throws SQLException;

    /** Whether the key was ever unbound from an agent by a rebind (a key still bound is found by agentByApiKey). */
    boolean wasEverAgentKey( int apiKeyId ) throws SQLException;
}
