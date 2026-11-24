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
package com.wikantik.livevalues;

import com.wikantik.api.core.Page;
import com.wikantik.api.exceptions.ProviderException;
import com.wikantik.api.livevalues.LiveValueExpander;
import com.wikantik.api.livevalues.LiveValues;
import com.wikantik.api.managers.PageManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Finds pages whose raw text carries {@code {{wiki:...}}} tokens that name nothing resolvable. Admin-only:
 * a {@code cluster.<path>.pages} name that resolves to nothing may be a typo, a cluster not created yet, or a
 * private cluster (private ones resolve like missing ones by design), so this listing must never reach readers.
 */
public final class LiveValueDrift {

    private static final Logger LOG = LogManager.getLogger( LiveValueDrift.class );

    /** Shown beside the listing. */
    public static final String CAVEAT = "A cluster.<path>.pages name listed here resolves to nothing: the "
            + "cluster may not exist yet, may be misspelled, or may be private (private clusters resolve like "
            + "missing ones). Do not copy this listing to a reader-facing surface.";

    private LiveValueDrift() {
    }

    /**
     * @param liveValues the service, or null (then no name resolves and only built-ins count as known)
     * @return one row per page: {@code pageName} and the sorted {@code names} that do not resolve
     */
    public static List< Map< String, Object > > scan( final PageManager pages, final LiveValues liveValues ) {
        final Set< String > resolving = liveValues == null ? Set.of() : liveValues.renderedValues().keySet();
        final List< Map< String, Object > > rows = new ArrayList<>();
        final java.util.Collection< Page > all;
        try {
            all = pages.getAllPages();
        } catch( final ProviderException e ) {
            LOG.warn( "live-value drift: page enumeration failed: {}", e.getMessage(), e );
            throw new IllegalStateException( "page enumeration failed", e );
        }
        for( final Page page : all ) {
            final String text = rawText( pages, page );
            final Set< String > bad = new TreeSet<>();
            for( final String name : LiveValueExpander.tokenNames( text ) ) {
                if( !LiveValueExpander.BUILT_IN_NAMES.contains( name ) && !resolving.contains( name ) ) {
                    bad.add( name );
                }
            }
            if( !bad.isEmpty() ) {
                final Map< String, Object > row = new LinkedHashMap<>();
                row.put( "pageName", page.getName() );
                row.put( "names", List.copyOf( bad ) );
                rows.add( row );
            }
        }
        return rows;
    }

    private static String rawText( final PageManager pages, final Page page ) {
        try {
            return pages.getPureText( page );
        } catch( final RuntimeException e ) {
            LOG.warn( "live-value drift: failed to read '{}', skipping: {}", page.getName(), e.getMessage() );
            return null;
        }
    }
}
