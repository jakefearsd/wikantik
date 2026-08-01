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
package com.wikantik.its.dense;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The fixture corpus and queries shared by this module's suites.
 *
 * <p>Extracted from {@link DenseBundleIT} so the corpus's own invariants can be checked
 * without standing up the world. That class seeds pages over HTTP and refuses to start
 * without a live embedder — correct for what it tests, but it meant the purely textual
 * zero-overlap guard (now {@link DenseProbeCorpusIT}) could only return a verdict when
 * the embedder happened to be up, which is exactly when it is least needed.</p>
 */
final class DenseProbeCorpus {

    private DenseProbeCorpus() {}

    /** The page the semantic query must retrieve. Named so it does NOT leak the query's words. */
    static final String TARGET = "DenseProbeFermentation";

    /**
     * ZERO-LEXICAL-OVERLAP QUERY. Every word here — how, do, i, look, after, my, sourdough,
     * starter — is absent from {@link #TARGET}'s entire source (frontmatter, heading and
     * body), which {@link DenseProbeCorpusIT} verifies. Lucene's StandardAnalyzer does not
     * stem, so no shared term survives tokenisation either.
     *
     * <p>The BM25 chunk index does hold these pages (it refreshes on save), so this is what
     * rules out a lexical shortcut rather than a precaution against a future one.</p>
     */
    static final String SEMANTIC_QUERY = "how do I look after my sourdough starter";

    /** A plainly-worded query used only for the response-shape assertions. */
    static final String SHAPE_QUERY = "inspecting a beehive before autumn";

    /**
     * Five short fixture pages on unrelated subjects. Only {@link #TARGET} is about
     * maintaining a live fermentation culture; the other four exist so that the target
     * ranking first is a real discrimination result rather than the only available answer.
     */
    static final Map< String, String > FIXTURES = new LinkedHashMap<>();

    static {
        FIXTURES.put( TARGET, """
            ---
            summary: Keeping a jar of wild yeast culture healthy on the kitchen counter.
            tags: [dense-probe]
            ---
            # Wild Yeast Culture Maintenance

            A jar of fermented grain paste kept on the kitchen counter hosts a stable
            colony of wild yeast and lactobacilli. Refresh it twice each day with equal
            weights of milled wheat and clean water, discarding half the mass beforehand
            so the colony never exhausts its supply of sugars. A healthy jar doubles in
            volume within six hours, smells pleasantly acidic, and floats when a spoonful
            is dropped into water. Refrigerate the jar to slow the colony down when
            travelling.
            """ );

        FIXTURES.put( "DenseProbeApiary", """
            ---
            summary: Seasonal inspection routine for a small backyard apiary.
            tags: [dense-probe]
            ---
            # Hive Inspection Routine

            Open each hive on a warm still afternoon when most foragers are away in the
            field. Lift the crown board slowly, check the brood pattern for an even laying
            queen, and count the frames of stores before the autumn dearth. Smoke calms the
            colony but heavy smoking drives the queen down. Record every inspection so
            swarming behaviour can be anticipated a season in advance.
            """ );

        FIXTURES.put( "DenseProbeGlaciology", """
            ---
            summary: Measuring mass balance on retreating alpine ice.
            tags: [dense-probe]
            ---
            # Mass Balance Measurement

            Stakes drilled into the ablation zone record surface lowering through the melt
            season, while snow pits dug near the accumulation basin give winter
            accumulation. Satellite altimetry supplements the ground network where crevasse
            fields make travel unsafe. The difference between the two terms gives the annual
            mass balance, the single most useful indicator of whether the ice is advancing
            or retreating.
            """ );

        FIXTURES.put( "DenseProbeLutherie", """
            ---
            summary: Repairing an open seam on a carved violin belly.
            tags: [dense-probe]
            ---
            # Open Seam Repair

            A seam that has opened along the rib line is repaired with hot hide glue, never
            with modern adhesives, so that a future restorer can take the instrument apart
            again. Warm a thin palette knife, work it gently into the joint to clear old
            glue, then clamp with spool clamps padded in cork. Leave the instrument to cure
            overnight before restringing.
            """ );

        FIXTURES.put( "DenseProbeHarbourPilotage", """
            ---
            summary: Boarding arrangements and bridge procedure for harbour pilots.
            tags: [dense-probe]
            ---
            # Boarding And Bridge Procedure

            The pilot boards by ladder on the lee side while the vessel maintains steerage
            way at reduced speed. On reaching the bridge the pilot exchanges a master-pilot
            information card covering draught, manoeuvring characteristics and tug
            arrangements. Helm orders are repeated back by the quartermaster, and the master
            retains command throughout the transit of the fairway.
            """ );
    }
    /** Lowercased alphabetic tokens, for the zero-overlap check. */
    static Set< String > words( final String text ) {
        final Set< String > out = new LinkedHashSet<>();
        for ( final String w : text.toLowerCase( Locale.ROOT ).split( "[^a-z]+" ) ) {
            if ( !w.isEmpty() ) out.add( w );
        }
        return out;
    }
}
