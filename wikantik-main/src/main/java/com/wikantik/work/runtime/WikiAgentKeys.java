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
import com.wikantik.work.WorkValidationException;

import java.util.function.Predicate;

/** Mints, re-mints and revokes the {@code work}-scoped API keys agents authenticate with. */
public final class WikiAgentKeys implements AgentKeys {

    /** The refusal to re-mint for an owner the user database no longer has (P31). */
    static final String OWNER_GONE = "This agent's key owner no longer exists. Name a new owner.";

    private final ApiKeyService service;
    private final Predicate< String > ownerExists;

    /**
     * @param ownerExists whether a login is still in the user database: a re-mint keeps the old key's owner, so an
     *                    owner who was deleted must be replaced by name instead
     */
    public WikiAgentKeys( final ApiKeyService service, final Predicate< String > ownerExists ) {
        this.service = service;
        this.ownerExists = ownerExists;
    }

    @Override
    public Minted mint( final String ownerLogin, final String label, final String createdBy ) {
        return generate( ownerLogin, label, createdBy );
    }

    /**
     * Works from a revoked old key too: it still names the owner, and re-minting from it is the recovery path. Refused
     * ({@link WorkValidationException} on {@code owner}) when that owner is no longer in the user database.
     */
    @Override
    public Minted remint( final int oldKeyId, final String label, final String createdBy ) {
        final ApiKeyService.Record old = service.findById( oldKeyId )
            .orElseThrow( () -> new WorkConflictException( "The agent's current API key (id " + oldKeyId
                + ") was not found, so it cannot be rotated." ) );
        if ( !ownerExists.test( old.principalLogin() ) ) {
            // No live credential may outlive its user: a deleted owner is never given a fresh key.
            throw new WorkValidationException( "owner", OWNER_GONE );
        }
        return generate( old.principalLogin(), label, createdBy );
    }

    @Override
    public void revoke( final int keyId, final String revokedBy ) {
        service.revoke( keyId, revokedBy );
    }

    /**
     * The only place a key is created here: scope is always {@code WORK}, never copied from another key; the owner is
     * the key's principal and the acting admin its creator.
     */
    private Minted generate( final String ownerLogin, final String label, final String createdBy ) {
        final ApiKeyService.Generated g = service.generate( ownerLogin, label, ApiKeyService.Scope.WORK, createdBy );
        return new Minted( g.record().id(), g.plaintext() );
    }
}
