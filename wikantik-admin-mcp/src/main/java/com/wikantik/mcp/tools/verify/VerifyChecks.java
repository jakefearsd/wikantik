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
import java.util.List;
import java.util.Set;

/**
 * Assembles the {@link VerifyCheck}s a {@code verify_pages} call runs. The order here is the order
 * of the keys in each page entry and in the summary. {@code existence} has no object: the tool
 * itself always reports {@code exists} and {@code version}.
 */
public final class VerifyChecks {

    private VerifyChecks() {
    }

    /** The checks selected by {@code activeChecks}, in output order. */
    public static List< VerifyCheck > active( final PageManager pageManager,
                                              final ReferenceManager referenceManager,
                                              final Set< String > activeChecks ) {
        final List< VerifyCheck > checks = new ArrayList<>();
        final boolean outbound = activeChecks.contains( "outbound_links" );
        final boolean broken = activeChecks.contains( "broken_links" );
        if ( outbound || broken ) {
            checks.add( new LinksCheck( pageManager, referenceManager, outbound, broken ) );
        }
        if ( activeChecks.contains( "backlinks" ) ) {
            checks.add( new BacklinksCheck( referenceManager ) );
        }
        if ( activeChecks.contains( "metadata_completeness" ) ) {
            checks.add( new MetadataCheck() );
        }
        if ( activeChecks.contains( "seo_readiness" ) ) {
            checks.add( new SeoCheck( pageManager ) );
        }
        if ( activeChecks.contains( "retrieval_readiness" ) ) {
            checks.add( new RetrievalCheck( pageManager ) );
        }
        return checks;
    }
}
