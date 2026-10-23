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
package com.wikantik.pagegraph.spine;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The {@link PageRetirementListener}s of one structural index, and their dispatch: each runs in
 * isolation, so one cleanup failing is logged and never stops the others. Copy-on-write —
 * listeners are registered at startup and read on every delete.
 */
final class PageRetirementListeners {

    private static final Logger LOG = LogManager.getLogger( PageRetirementListeners.class );

    private final List< PageRetirementListener > listeners = new CopyOnWriteArrayList<>();

    /** Adds a listener; {@code null} is ignored. */
    void add( final PageRetirementListener listener ) {
        if ( listener != null ) {
            listeners.add( listener );
        }
    }

    /** Tells every listener the page's canonical_id was retired; a {@code null} id means nothing was. */
    void fire( final String slug, final String canonicalId ) {
        if ( canonicalId == null ) {
            return;
        }
        for ( final PageRetirementListener l : listeners ) {
            try {
                l.pageRetired( slug, canonicalId );
            } catch ( final RuntimeException e ) {
                LOG.warn( "page-retirement cleanup failed for '{}' (canonical_id {}): {}",
                          slug, canonicalId, e.getMessage(), e );
            }
        }
    }
}
