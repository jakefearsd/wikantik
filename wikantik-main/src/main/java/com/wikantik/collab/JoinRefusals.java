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
package com.wikantik.collab;

import com.wikantik.collab.CollabSessionRegistry.Refused;
import com.wikantik.collab.model.JoinRequest;
import com.wikantik.collab.model.PageState;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * The refusals {@link CollabSessionRegistry#join} makes before it touches a slot: 403 {@code forbidden} for a caller
 * without {@code edit}, or without {@code createPages} on a page that does not exist yet, then 409
 * {@code derived-page} (section 9).
 */
final class JoinRefusals {

    private static final Logger LOG = LogManager.getLogger( JoinRefusals.class );
    private static final String DERIVED_FROM = "derived_from";

    private JoinRefusals() {
    }

    /**
     * Null to go on, else the refusal. {@code latest} is null when the page does not exist. 403 comes first: a
     * non-editor learns nothing more.
     */
    static Refused refuse( final CollabPermissions permissions, final String pageName, final JoinRequest r,
                           final PageState latest ) {
        if( !mayJoin( permissions, r, pageName, latest == null ) ) {
            return new Refused( 403, CollabSessionRegistry.FORBIDDEN );
        }
        if( latest != null && latest.meta().containsKey( DERIVED_FROM ) ) {
            return new Refused( 409, CollabSessionRegistry.DERIVED );
        }
        return null;
    }

    /** {@code edit}, plus {@code createPages} for a new page; a check that throws fails closed (logged). */
    private static boolean mayJoin( final CollabPermissions permissions, final JoinRequest r, final String pageName,
                                    final boolean create ) {
        final Object who = r.wikiSession();
        try {
            return permissions.canEdit( who, pageName ) && ( !create || permissions.canCreate( who, pageName ) );
        } catch( final RuntimeException e ) {
            LOG.warn( "collab: the permission check for {} joining {} failed; refusing: {}", r.authorKey(), pageName,
                e.toString() );
            return false;
        }
    }
}
