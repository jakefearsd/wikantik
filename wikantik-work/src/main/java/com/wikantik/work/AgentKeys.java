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

/** The port through which the work module mints and revokes the API keys agents authenticate with. */
public interface AgentKeys {

    /** A freshly minted key: its id and the one-time token. */
    record Minted( int keyId, String token ) {
    }

    /** Mints a work-scoped key owned by {@code ownerLogin}. */
    Minted mint( String ownerLogin, String label );

    /**
     * Mints a work-scoped key owned by the same principal as {@code oldKeyId}, so a rotated agent key stays owned
     * by the same person. Mints nothing and throws {@link WorkConflictException} when the old key cannot be found.
     */
    Minted remint( int oldKeyId, String label );

    /** Revokes a key. */
    void revoke( int keyId, String revokedBy );

    /** No key service available: minting always fails. */
    AgentKeys NONE = new AgentKeys() {
        @Override
        public Minted mint( final String ownerLogin, final String label ) {
            throw new WorkConflictException( "Agent keys are not available." );
        }

        @Override
        public Minted remint( final int oldKeyId, final String label ) {
            throw new WorkConflictException( "Agent keys are not available." );
        }

        @Override
        public void revoke( final int keyId, final String revokedBy ) {
            // nothing was ever minted
        }
    };
}
