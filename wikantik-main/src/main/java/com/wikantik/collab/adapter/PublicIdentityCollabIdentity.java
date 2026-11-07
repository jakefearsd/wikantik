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
package com.wikantik.collab.adapter;

import com.wikantik.api.identity.PublicIdentity;
import com.wikantik.collab.CollabIdentity;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Objects;

/**
 * {@link CollabIdentity} over {@link PublicIdentity}: always a public display name, never the stored login. When
 * resolution yields nothing or fails the answer is {@link PublicIdentity#UNKNOWN}, not the key.
 */
public final class PublicIdentityCollabIdentity implements CollabIdentity {

    private static final Logger LOG = LogManager.getLogger( PublicIdentityCollabIdentity.class );

    private final PublicIdentity identity;

    public PublicIdentityCollabIdentity( final PublicIdentity identity ) {
        this.identity = Objects.requireNonNull( identity, "identity" );
    }

    @Override
    public String displayName( final String authorKey ) {
        try {
            final String name = identity.displayName( authorKey );
            return name == null || name.isBlank() ? PublicIdentity.UNKNOWN : name;
        } catch( final RuntimeException e ) {
            LOG.warn( "Display-name resolution failed; answering '{}': {}", PublicIdentity.UNKNOWN, e.getMessage(), e );
            return PublicIdentity.UNKNOWN;
        }
    }
}
