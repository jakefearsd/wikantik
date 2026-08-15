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
package com.wikantik.extractcli;

import java.util.List;

/**
 *  Everything on which the repository corpus and production disagree.
 *
 *  @param onlyLocal  slugs present in the repository but not in production
 *  @param onlyRemote slugs present in production but not in the repository
 *  @param deltas     frontmatter fields that differ on pages present in both
 */
public record CorpusDivergence(
        List< String > onlyLocal,
        List< String > onlyRemote,
        List< FieldDelta > deltas ) {

    public CorpusDivergence {
        onlyLocal  = onlyLocal  == null ? List.of() : List.copyOf( onlyLocal );
        onlyRemote = onlyRemote == null ? List.of() : List.copyOf( onlyRemote );
        deltas     = deltas     == null ? List.of() : List.copyOf( deltas );
    }

    /** Whether the two corpora agree completely. */
    public boolean isEmpty() {
        return onlyLocal.isEmpty() && onlyRemote.isEmpty() && deltas.isEmpty();
    }

    /** Total number of findings, for a one-line summary. */
    public int findingCount() {
        return onlyLocal.size() + onlyRemote.size() + deltas.size();
    }

    /** Human-readable report, one finding per line. */
    public String render() {
        if ( isEmpty() ) {
            return "corpora agree: no divergence";
        }
        final StringBuilder sb = new StringBuilder();
        onlyLocal.forEach( s -> sb.append( "only-in-repo  " ).append( s ).append( '\n' ) );
        onlyRemote.forEach( s -> sb.append( "only-in-prod  " ).append( s ).append( '\n' ) );
        deltas.forEach( d -> sb.append( "differs       " ).append( d.slug() )
                               .append( '.' ).append( d.field() )
                               .append( ": repo=" ).append( d.local() )
                               .append( " prod=" ).append( d.remote() ).append( '\n' ) );
        return sb.toString();
    }
}
