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
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * A view of an ACL that cannot change, entries included: what {@link DefaultAclManager#getPermissions} hands out and
 * caches. Every caller shares it, so nothing may widen or narrow it — not its entry list, and not an entry's principal or
 * permissions.
 */
final class ReadOnlyAcl implements Acl {

    private static final String REFUSED = "the enforced ACL of a page cannot be changed";

    private final Acl acl;
    private final List< AclEntry > views;
    private final Map< AclEntry, AclEntry > viewOf = new IdentityHashMap<>();

    ReadOnlyAcl( final Acl acl ) {
        this.acl = acl;
        final List< AclEntry > out = new ArrayList<>();
        for ( final Enumeration< AclEntry > entries = acl.aclEntries(); entries.hasMoreElements(); ) {
            final AclEntry entry = entries.nextElement();
            final AclEntry view = new ReadOnlyEntry( entry );
            viewOf.put( entry, view );
            out.add( view );
        }
        views = List.copyOf( out );
    }

    @Override
    public boolean addEntry( final AclEntry entry ) {
        throw new UnsupportedOperationException( REFUSED );
    }

    @Override
    public boolean removeEntry( final AclEntry entry ) {
        throw new UnsupportedOperationException( REFUSED );
    }

    @Override
    public Enumeration< AclEntry > aclEntries() {
        return Collections.enumeration( views );
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
        final AclEntry entry = acl.getAclEntry( principal );
        return entry == null ? null : viewOf.get( entry );
    }

    @Override
    public String toString() {
        return acl.toString();
    }

    /** An entry that answers like its delegate and refuses every change. */
    private static final class ReadOnlyEntry implements AclEntry {
        private final AclEntry entry;

        ReadOnlyEntry( final AclEntry entry ) {
            this.entry = entry;
        }

        @Override
        public boolean addPermission( final Permission permission ) {
            throw new UnsupportedOperationException( REFUSED );
        }

        @Override
        public boolean removePermission( final Permission permission ) {
            throw new UnsupportedOperationException( REFUSED );
        }

        @Override
        public boolean setPrincipal( final Principal user ) {
            throw new UnsupportedOperationException( REFUSED );
        }

        @Override
        public boolean checkPermission( final Permission permission ) {
            return entry.checkPermission( permission );
        }

        @Override
        public Principal getPrincipal() {
            return entry.getPrincipal();
        }

        @Override
        public Enumeration< Permission > permissions() {
            return entry.permissions();
        }

        @Override
        public String toString() {
            return entry.toString();
        }
    }
}
