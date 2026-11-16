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
import java.util.Optional;

/**
 * Agents' own credentials ({@code work_agent_credential}): a hashed token that names one agent and nothing else.
 * No person's access rides on it, and the wiki's API-key store never sees it. At most one is live per agent.
 */
public interface WorkCredentialStore {

    /** A credential just issued; the only time the token exists outside the caller that presents it. */
    record Issued( long credentialId, String token ) {

        /** Never prints the token. */
        @Override
        public String toString() {
            return "Issued[credentialId=" + credentialId + ", token=***]";
        }
    }

    /** A live credential and the agent it names. */
    record Live( long credentialId, long agentId ) {
    }

    /** One transaction: revokes the agent's live credential, if any, and inserts a new one. */
    Issued issue( long agentId, String createdBy ) throws SQLException;

    /** The live credential with this token hash; empty when unknown or revoked. A failure is an exception. */
    Optional< Live > findLive( String tokenHash ) throws SQLException;

    /** Revokes every live credential of the agent; returns how many. */
    int revokeAll( long agentId ) throws SQLException;

    /** Records a use, at most about once a minute per credential, so a read does not become a write per request. */
    void touch( long credentialId ) throws SQLException;
}
