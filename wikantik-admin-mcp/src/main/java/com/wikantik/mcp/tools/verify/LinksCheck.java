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
package com.wikantik.mcp.tools.verify;

import com.wikantik.api.managers.PageManager;
import com.wikantik.api.managers.ReferenceManager;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Outbound and broken links. Gated by {@code outbound_links} and {@code broken_links} together:
 * either one makes the reference lookup happen, and each controls only its own keys.
 */
public final class LinksCheck implements VerifyCheck {

    private final PageManager pageManager;
    private final ReferenceManager referenceManager;
    private final boolean outbound;
    private final boolean broken;

    public LinksCheck( final PageManager pageManager, final ReferenceManager referenceManager,
                       final boolean outbound, final boolean broken ) {
        this.pageManager = pageManager;
        this.referenceManager = referenceManager;
        this.outbound = outbound;
        this.broken = broken;
    }

    @Override
    public String name() {
        return "links";
    }

    @Override
    public void apply( final PageContext page, final Map< String, Object > entry, final VerifyTotals totals ) {
        final Collection< String > refersTo = referenceManager.findRefersTo( page.pageName() );
        final List< String > outboundLinks = new ArrayList<>( refersTo );
        Collections.sort( outboundLinks );

        if ( outbound ) {
            entry.put( "outboundLinks", outboundLinks );
        }

        if ( broken ) {
            final List< String > brokenLinks = new ArrayList<>();
            for ( final String target : outboundLinks ) {
                if ( pageManager.getPage( target ) == null ) {
                    brokenLinks.add( target );
                }
            }
            Collections.sort( brokenLinks );
            entry.put( "brokenLinks", brokenLinks );
            totals.addBrokenLinks( brokenLinks.size() );
        }
    }

    @Override
    public void summarise( final VerifyTotals totals, final Map< String, Object > summary ) {
        if ( broken ) {
            summary.put( "totalBrokenLinks", totals.brokenLinks() );
        }
    }
}
