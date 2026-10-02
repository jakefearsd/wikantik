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
package com.wikantik.importer;

import java.util.LinkedHashSet;
import java.util.Set;

/** Per-call mutable state shared by the body rewriting passes. */
final class RewriteCtx {
    final String fromPath;
    final VaultTargets targets;
    int blockRefs;
    int comments;
    private String fullBody;
    private int runStart;
    final Set< String > unresolved = new LinkedHashSet<>();

    RewriteCtx( final String fromPath, final VaultTargets targets ) {
        this.fromPath = fromPath;
        this.targets = targets;
    }

    /** Records an unresolved page target once per distinct name. */
    void unresolvedPage( final String t ) {
        unresolved.add( t );
    }

    /** Cuts a block reference off a heading; returns the remaining heading (possibly null) and counts it. */
    String cutBlock( final String frag ) {
        if ( frag == null ) {
            return null;
        }
        if ( frag.startsWith( "^" ) ) {
            blockRefs++;
            return null;
        }
        final int i = frag.indexOf( "#^" );
        if ( i >= 0 ) {
            blockRefs++;
            return frag.substring( 0, i );
        }
        return frag;
    }

    /** Records the full body and the offset of the prose run about to be transformed. */
    void enterRun( final String fullBody, final int runStart ) {
        this.fullBody = fullBody;
        this.runStart = runStart;
    }

    /**
     * True when the whole original line containing {@code pos} (an offset within the current prose run) starts with a
     * table pipe, even if the line began in an earlier segment (for example before an inline code span).
     */
    boolean inTableRow( final String prose, final int pos ) {
        final int nl = prose.lastIndexOf( '\n', pos - 1 );
        if ( nl >= 0 || fullBody == null ) {
            return prose.substring( nl + 1 ).stripLeading().startsWith( "|" );
        }
        final int lineStart = fullBody.lastIndexOf( '\n', runStart - 1 ) + 1;
        return ( fullBody.substring( lineStart, runStart ) + prose ).stripLeading().startsWith( "|" );
    }
}
