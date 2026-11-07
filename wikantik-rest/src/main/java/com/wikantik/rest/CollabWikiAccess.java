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
package com.wikantik.rest;

import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Session;
import com.wikantik.api.spi.Wiki;
import com.wikantik.auth.AuthorKeys;
import com.wikantik.auth.permissions.PermissionFilter;
import com.wikantik.collab.adapter.WikiCollabPermissions;
import com.wikantik.rest.CollabResource.Caller;
import jakarta.servlet.http.HttpServletRequest;

import java.util.function.Supplier;

/**
 * {@link CollabResource.Access} over the wiki: the caller is the request's authenticated wiki session (its author key,
 * A6, and the session itself, A5); {@code view} goes through the same quiet {@link PermissionFilter} check as the rest of
 * {@code /api}; {@code edit}/{@code createPages} through {@link WikiCollabPermissions}, which caches nothing (A5).
 */
final class CollabWikiAccess implements CollabResource.Access {

    private final Supplier< Engine > engine;

    CollabWikiAccess( final Supplier< Engine > engine ) {
        this.engine = engine;
    }

    @Override
    public Caller caller( final HttpServletRequest request ) {
        final Session session = Wiki.session().find( engine.get(), request );
        if( session == null || !session.isAuthenticated() ) {
            return null;
        }
        return new Caller( AuthorKeys.authorKeyFor( session ), session );
    }

    @Override
    public boolean mayView( final Caller caller, final String pageName ) {
        return new PermissionFilter( engine.get() ).canAccessQuietly( ( Session ) caller.wikiSession(), pageName,
            "view" );
    }

    @Override
    public boolean mayEdit( final Caller caller, final String pageName, final boolean create ) {
        final WikiCollabPermissions p = new WikiCollabPermissions( engine.get() );
        return p.canEdit( caller.wikiSession(), pageName ) && ( !create || p.canCreate( caller.wikiSession(), pageName ) );
    }
}
