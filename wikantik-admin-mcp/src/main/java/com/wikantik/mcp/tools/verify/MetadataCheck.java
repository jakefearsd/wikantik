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

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Frontmatter completeness against the standard fields. */
public final class MetadataCheck implements VerifyCheck {

    private static final Set< String > STANDARD_METADATA_FIELDS = Set.of( "type", "tags", "summary" );

    @Override
    public String name() {
        return "metadata_completeness";
    }

    @Override
    public void apply( final PageContext page, final Map< String, Object > entry, final VerifyTotals totals ) {
        final Map< String, Object > metadata = page.metadata();
        if ( !metadata.isEmpty() ) {
            entry.put( "metadata", new LinkedHashMap<>( metadata ) );
        }

        final List< String > missing = new ArrayList<>();
        for ( final String field : STANDARD_METADATA_FIELDS ) {
            if ( !metadata.containsKey( field ) ) {
                missing.add( field );
            }
        }
        Collections.sort( missing );
        entry.put( "missingMetadata", missing );

        if ( !missing.isEmpty() ) {
            totals.metadataIssues().add( page.pageName() + " missing: " + String.join( ", ", missing ) );
        }
    }

    @Override
    public void summarise( final VerifyTotals totals, final Map< String, Object > summary ) {
        summary.put( "metadataIssues", totals.metadataIssues() );
    }
}
