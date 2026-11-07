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

import com.wikantik.api.identity.IdentityDisplay;
import com.wikantik.api.identity.PublicIdentity;
import com.wikantik.collab.CollabIdentity;

import java.util.Objects;

/**
 * {@link CollabIdentity} over {@link PublicIdentity}: a public display name, never the stored login. When no display
 * name resolves the answer is null (ruling R14), so the session's own labels apply: a participant's {@code "Editor <n>"},
 * which keeps several nameless authors apart, and the {@code "Unknown contributor"} of the contributor list. No name
 * resolves when {@link PublicIdentity} answers blank, one of its placeholders ({@link PublicIdentity#UNKNOWN},
 * {@link PublicIdentity#UNKNOWN_CONTRIBUTOR}), the key itself (it passes an unknown principal through unchanged, and
 * that is the login), or fails. {@link PublicIdentity#FORMER_MEMBER} is a public label and is kept.
 */
public final class PublicIdentityCollabIdentity implements CollabIdentity {

    private final PublicIdentity identity;

    public PublicIdentityCollabIdentity( final PublicIdentity identity ) {
        this.identity = Objects.requireNonNull( identity, "identity" );
    }

    /** {@link IdentityDisplay#ofContributor}: the one resolution the page history shares. */
    @Override
    public String displayName( final String authorKey ) {
        return IdentityDisplay.ofContributor( identity, authorKey );
    }
}
