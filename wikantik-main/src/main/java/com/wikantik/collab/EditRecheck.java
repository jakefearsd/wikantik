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
}
