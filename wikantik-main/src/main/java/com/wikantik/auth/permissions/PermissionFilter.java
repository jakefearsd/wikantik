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
package com.wikantik.auth.permissions;

import com.wikantik.auth.AuthorizationManager;
import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Page;
import com.wikantik.api.core.Session;
import com.wikantik.api.managers.PageManager;

import java.security.Permission;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Single decision point for page-level access control.
 *
 * <p>Resolves a session + page name + action ({@code "view"}, {@code "edit"},
 * etc.) into an allow/deny decision by:
 *
 * <ol>
 *   <li>looking the page up via {@link PageManager},</li>
 *   <li>constructing a {@link PagePermission} — via
 *       {@link PermissionFactory#getPagePermission(Page, String)} when the
 *       page exists (so inline {@code [{ALLOW ...}]} ACLs are honoured), or
 *       via {@code new PagePermission(appName + ":" + pageName, action)} when
 *       it does not (so policy grants like {@code wiki:*} still match),
 *       and</li>
 *   <li>asking {@link AuthorizationManager#checkPermission} to decide.</li>
 * </ol>
 *
 * <p>This logic previously lived as a pair of protected helpers on
 * {@code RestServletBase}. Extracting it lets search, MCP tools, and future
 * retrieval services apply identical rules from non-servlet call sites.
 */
public class PermissionFilter {

    private final Engine engine;

    public PermissionFilter( final Engine engine ) {
        this.engine = engine;
    }

    /**
     * Returns {@code true} if {@code session} is permitted to perform
     * {@code action} on {@code pageName}. Does not throw for unknown pages —
     * a permission is constructed from the page name directly so policy-level
     * grants still apply.
     */
    public boolean canAccess( final Session session, final String pageName, final String action ) {
        final Page page = engine.getManager( PageManager.class ).getPage( pageName );
        final Permission perm = ( page != null )
                ? PermissionFactory.getPagePermission( page, action )
                : new PagePermission( engine.getApplicationName() + ":" + pageName, action );
        return engine.getManager( AuthorizationManager.class ).checkPermission( session, perm );
    }

    /**
     * Filters {@code pageNames} to the subset the session may perform
     * {@code action} on. Preserves input order. Intended for retrieval
     * consumers that need to drop forbidden hits before returning results.
     */
    public List< String > filterAccessible( final Session session,
                                             final Collection< String > pageNames,
                                             final String action ) {
        final List< String > out = new ArrayList<>( pageNames.size() );
        for ( final String name : pageNames ) {
            if ( canAccess( session, name, action ) ) {
                out.add( name );
            }
        }
        return out;
    }
}
