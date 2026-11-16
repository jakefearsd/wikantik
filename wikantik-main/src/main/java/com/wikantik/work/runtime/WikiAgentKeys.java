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
package com.wikantik.work.runtime;

import com.wikantik.auth.apikeys.ApiKeyService;
import com.wikantik.work.AgentKeys;
import com.wikantik.work.WorkConflictException;

/** Mints, re-mints and revokes the {@code work}-scoped API keys agents authenticate with. */
public final class WikiAgentKeys implements AgentKeys {

    private final ApiKeyService service;

    public WikiAgentKeys( final ApiKeyService service ) {
        this.service = service;
    }

    @Override
    public Minted mint( final String ownerLogin, final String label ) {
        return generate( ownerLogin, label );
    }

    @Override
    public Minted remint( final int oldKeyId, final String label ) {
        final ApiKeyService.Record old = service.findById( oldKeyId )
            .orElseThrow( () -> new WorkConflictException( "The agent's current API key (id " + oldKeyId
                + ") was not found, so it cannot be rotated." ) );
        return generate( old.principalLogin(), label );
    }

    @Override
    public void revoke( final int keyId, final String revokedBy ) {
        service.revoke( keyId, revokedBy );
    }

    /** The only place a key is created here: scope is always {@code WORK}, never copied from another key. */
    private Minted generate( final String ownerLogin, final String label ) {
        final ApiKeyService.Generated g = service.generate( ownerLogin, label, ApiKeyService.Scope.WORK, ownerLogin );
        return new Minted( g.record().id(), g.plaintext() );
    }
}
