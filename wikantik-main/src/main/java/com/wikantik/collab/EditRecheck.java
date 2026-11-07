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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A5: the participants who no longer hold {@code edit} on the page, checked after every publish and merge (the body may
 * have changed its access rules). Called with no session lock held: an authorization check may be slow. Fails closed: a
 * check that throws counts as lost.
 */
final class EditRecheck {

    private static final Logger LOG = LogManager.getLogger( EditRecheck.class );

    private EditRecheck() {
    }

    /** The ids of the participants in {@code all} that {@code permissions} no longer lets edit {@code page}. */
    static List< String > lost( final UUID session, final List< Participant > all, final CollabPermissions permissions,
                                final String page ) {
        final List< String > out = new ArrayList<>();
        for( final Participant p : all ) {
            boolean ok;
            try {
                ok = permissions.canEdit( p.wikiSession, page );
            } catch( final RuntimeException e ) {
                LOG.warn( "collab session {}: the edit check of {} on {} failed; evicting: {}", session, p.id, page,
                    e.getMessage() );
                ok = false;
            }
            if( !ok ) {
                LOG.info( "collab session {}: participant {} lost edit on {}; evicted", session, p.id, page );
                out.add( p.id );
            }
        }
        return out;
    }

    /** True when a publish of a {@code frozen} draft must be refused 403 {@code no-create} ({@link #mayCreate}). */
    static boolean recreateRefused( final boolean frozen, final UUID session, final Participant p,
                                    final CollabPermissions permissions, final String page ) {
        return frozen && !mayCreate( session, p, permissions, page );
    }

    /**
     * Ruling F3': the guard {@code p}'s publish may pass, or null. No guard is passed trivially; a guard that does not
     * restrict ({@link CollabPermissions#restricts}: the engine reads no rules in it) is passed by anyone; one that
     * restricts, or cannot be evaluated (fail closed, logged), only by an administrator.
     */
    static String guardLetPast( final UUID session, final Participant p, final CollabPermissions permissions,
                                final String guard, final String page ) {
        if( guard == null ) {
            return null;
        }
        boolean restricts;
        try {
            restricts = permissions.restricts( page, guard );
        } catch( final RuntimeException e ) {
            LOG.warn( "collab session {}: the guard of {} could not be evaluated; only an administrator may publish: {}",
                session, page, e.getMessage() );
            restricts = true;
        }
        return !restricts || isAdmin( session, p, permissions ) ? guard : null;
    }

    /**
     * Ruling F3: whether {@code p} is an administrator, asked uncached with no session lock held. Fails closed: a check
     * that throws is a refusal (logged).
     */
    static boolean isAdmin( final UUID session, final Participant p, final CollabPermissions permissions ) {
        try {
            return permissions.isAdmin( p.wikiSession );
        } catch( final RuntimeException e ) {
            LOG.warn( "collab session {}: the administrator check of {} failed; treating it as no administrator: {}",
                session, p.id, e.getMessage() );
            return false;
        }
    }

    /**
     * A5/E28: whether {@code p} may create {@code page} (publishing a frozen draft recreates its page), asked uncached
     * with no session lock held. Fails closed: a check that throws is a refusal (logged).
     */
    static boolean mayCreate( final UUID session, final Participant p, final CollabPermissions permissions,
                              final String page ) {
        try {
            return permissions.canCreate( p.wikiSession, page );
        } catch( final RuntimeException e ) {
            LOG.warn( "collab session {}: the createPages check of {} on {} failed; refusing the recreate: {}", session,
                p.id, page, e.getMessage() );
            return false;
        }
    }
}
