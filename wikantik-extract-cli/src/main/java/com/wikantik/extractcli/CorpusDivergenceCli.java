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

import java.io.PrintStream;
import java.nio.file.Path;

/**
 *  Reports where the repository corpus and a live wiki disagree.
 *
 *  <pre>
 *  java -cp wikantik-extract-cli.jar com.wikantik.extractcli.CorpusDivergenceCli \
 *       docs/wikantik-pages https://wiki.wikantik.com [--check]
 *  </pre>
 *
 *  <p>Exit codes: {@code 0} agreement (or divergence without {@code --check}),
 *  {@code 1} divergence under {@code --check}, {@code 2} a snapshot could not be read
 *  completely and comparison was refused.</p>
 *
 *  <p>Phase 0b of ClusterDeclarationDesign. The repository and production hold
 *  <b>different corpora</b>, so a corpus-wide plan derived from the checkout can be wrong
 *  for production — this is the guardrail that makes that visible before a migration acts
 *  on it, and the reason exit code 2 is distinct from exit code 1.</p>
 */
public final class CorpusDivergenceCli {

    private CorpusDivergenceCli() {
    }

    /**
     *  Compares two already-loaded snapshots and writes a report.
     *
     *  @return the process exit code
     */
    static int run( final CorpusSnapshot local, final CorpusSnapshot remote,
                     final boolean check, final PrintStream out ) {
        final CorpusDivergence divergence;
        try {
            divergence = CorpusDiff.compare( local, remote );
        } catch ( final IllegalStateException refused ) {
            // Deliberately before any findings are printed: a report built from a partial
            // corpus is worse than no report, because it reads as authoritative.
            out.println( "REFUSED: " + refused.getMessage() );
            return 2;
        }

        out.println( divergence.render() );
        if ( divergence.isEmpty() ) {
            return 0;
        }
        out.println( divergence.findingCount() + " finding(s): "
                             + divergence.onlyLocal().size() + " only-in-repo, "
                             + divergence.onlyRemote().size() + " only-in-prod, "
                             + divergence.deltas().size() + " differing field(s)" );
        return check ? 1 : 0;
    }

    public static void main( final String[] args ) {
        if ( args.length < 2 ) {
            System.err.println( "usage: CorpusDivergenceCli <corpus-dir> <wiki-base-url> [--check]" );
            System.exit( 64 );
            return;
        }
        boolean check = false;
        for ( final String a : args ) {
            if ( "--check".equals( a ) ) {
                check = true;
            }
        }
        final CorpusSnapshot local = new LocalCorpusSource( Path.of( args[ 0 ] ) ).load();
        final CorpusSnapshot remote =
                new RemoteCorpusSource( RemoteCorpusSource.httpFetcher( args[ 1 ] ) ).load();
        System.exit( run( local, remote, check, System.out ) );
    }
}
