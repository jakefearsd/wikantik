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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/**
 *  Compares the repository corpus against production and reports where they disagree.
 *
 *  <p>This is the guardrail for ClusterDeclarationDesign Phase 0b. The repository corpus
 *  ({@code docs/wikantik-pages/}) and the production page store are <b>different corpora,
 *  not two copies of one</b>: production holds pages the repository lacks and vice versa,
 *  so a corpus-wide plan derived from the checkout can be wrong for production. During the
 *  Phase 0 migration exactly that happened — a hub was created for a cluster that was
 *  headless in the repository but already declared in production, introducing the very
 *  duplicate declaration the design forbids.</p>
 *
 *  <p>Comparison is <b>refused</b> against an incomplete snapshot. That is the load-bearing
 *  rule: a transport that silently drops pages turns every unread page into a phantom
 *  "missing from production", which is worse than no report at all because it looks
 *  authoritative.</p>
 */
public final class CorpusDiff {

    private CorpusDiff() {
    }

    /**
     *  Compares two corpus snapshots.
     *
     *  @param local  the repository side
     *  @param remote the production side
     *  @return everything the two disagree on
     *  @throws IllegalStateException when either snapshot failed to read any page — an
     *          incomplete side cannot be distinguished from a genuinely smaller one
     */
    public static CorpusDivergence compare( final CorpusSnapshot local, final CorpusSnapshot remote ) {
        requireComplete( local );
        requireComplete( remote );

        final List< String > onlyLocal = new ArrayList<>();
        final List< String > onlyRemote = new ArrayList<>();
        final List< FieldDelta > deltas = new ArrayList<>();

        for ( final String slug : new TreeSet<>( local.pages().keySet() ) ) {
            final PageFacts l = local.pages().get( slug );
            final PageFacts r = remote.pages().get( slug );
            if ( r == null ) {
                onlyLocal.add( slug );
                continue;
            }
            addDelta( deltas, slug, "canonical_id", l.canonicalId(), r.canonicalId() );
            addDelta( deltas, slug, "cluster", l.cluster(), r.cluster() );
            addDelta( deltas, slug, "type", l.type(), r.type() );
        }
        for ( final String slug : new TreeSet<>( remote.pages().keySet() ) ) {
            if ( !local.pages().containsKey( slug ) ) {
                onlyRemote.add( slug );
            }
        }
        return new CorpusDivergence( onlyLocal, onlyRemote, deltas );
    }

    private static void requireComplete( final CorpusSnapshot snapshot ) {
        if ( !snapshot.complete() ) {
            throw new IllegalStateException(
                    "refusing to compare: the '" + snapshot.name() + "' snapshot is incomplete ("
                            + snapshot.errors().size() + " page(s) could not be read), so a page missing "
                            + "from it cannot be told apart from a page that was never fetched. First error: "
                            + snapshot.errors().get( 0 ) );
        }
    }

    private static void addDelta( final List< FieldDelta > out, final String slug, final String field,
                                   final String local, final String remote ) {
        if ( !Objects.equals( local, remote ) ) {
            out.add( new FieldDelta( slug, field, local, remote ) );
        }
    }
}
