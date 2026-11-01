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
package com.wikantik.auth.acl;

import com.wikantik.api.core.Acl;
import com.wikantik.api.core.AclEntry;

import java.security.Permission;
import java.security.Principal;
import java.util.Enumeration;

/**
 * A view of an ACL whose entry list cannot change: what {@link DefaultAclManager#getPermissions} hands out and caches. (Entries still
 * resolve a principal named before it existed in place — {@code DefaultAuthorizationManager} does, idempotently.)
 */
final class ReadOnlyAcl implements Acl {
    private final Acl acl;

    ReadOnlyAcl( final Acl acl ) {
        this.acl = acl;
    }

    @Override
    public boolean addEntry( final AclEntry entry ) {
        throw new UnsupportedOperationException( "the enforced ACL of a page cannot be changed" );
    }

    @Override
    public boolean removeEntry( final AclEntry entry ) {
        throw new UnsupportedOperationException( "the enforced ACL of a page cannot be changed" );
    }

    @Override
    public Enumeration< AclEntry > aclEntries() {
        return acl.aclEntries();
    }

    @Override
    public boolean isEmpty() {
        return acl.isEmpty();
    }

    @Override
    public Principal[] findPrincipals( final Permission permission ) {
        return acl.findPrincipals( permission );
    }

    @Override
    public AclEntry getAclEntry( final Principal principal ) {
        return acl.getAclEntry( principal );
    }

    @Override
    public String toString() {
        return acl.toString();
    }
}
