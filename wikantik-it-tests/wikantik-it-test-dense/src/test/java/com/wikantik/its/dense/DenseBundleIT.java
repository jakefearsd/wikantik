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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.IOException;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * End-to-end coverage of dense retrieval and the RAG context bundle against a live
 * CPU embedding provider — the path {@code GET /api/bundle} actually ships and which,
 * before this module existed, no integration test touched anywhere in the repo.
 *
 * <p>The suite seeds five short, topically-disjoint fixture pages through
 * {@code PUT /api/pages/{name}}, which drives the real production pipeline:
 * page save → {@code ChunkProjector.postSave} → {@code AsyncEmbeddingIndexListener}
 * → embedding → Lucene HNSW upsert. Nothing is inserted into the database behind the
 * application's back.</p>
 *
 * <p><b>The load-bearing design point is the non-vacuity guard</b> in
 * {@link #bundleFindsASectionNoLexicalSearchCouldFind()}. Its query shares not one
 * word with the page it must retrieve — {@link #querySharesNoWordWithTheTargetPage()}
 * asserts that mechanically, so a later edit cannot quietly reintroduce a lexical
 * shortcut — and it demands that page rank <em>first</em>, ahead of four unrelated
 * fixtures. A BM25 fallback cannot produce that result: with zero shared terms the
 * lexical ranker has nothing to score. The match has to come from the embedding.
 * Without that constraint the suite would pass with the embedder switched off,
 * rebuilding exactly the kind of vacuous green removed elsewhere in this codebase.</p>
 */
@TestMethodOrder( MethodOrderer.OrderAnnotation.class )
class DenseBundleIT {

    private static final String BASE = System.getProperty( "it-wikantik.base.url" );
    private static final String EMBEDDER = "http://localhost:11435";

    /** The page the semantic query must retrieve. Named so it does NOT leak the query's words. */
    private static final String TARGET = "DenseProbeFermentation";

    /**
     * NON-VACUITY GUARD QUERY. Every word here — how, do, i, look, after, my, sourdough,
     * starter — is absent from {@link #TARGET}'s entire source (frontmatter, heading and
     * body), which {@link #querySharesNoWordWithTheTargetPage()} verifies. Lucene's
     * StandardAnalyzer does not stem, so no shared term survives tokenisation either.
     */
    private static final String SEMANTIC_QUERY = "how do I look after my sourdough starter";

    /** A plainly-worded query used only for the response-shape assertions. */
    private static final String SHAPE_QUERY = "inspecting a beehive before autumn";

    /**
     * Five short fixture pages on unrelated subjects. Only {@link #TARGET} is about
     * maintaining a live fermentation culture; the other four exist so that "the target
     * ranked first" is a real discrimination result rather than the only available answer.
     */
    private static final Map< String, String > FIXTURES = new LinkedHashMap<>();

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

    /**
     * Budget for the async chunk → embed → HNSW-upsert pipeline to catch up.
     *
     * <p>Measured happy path for the whole class — five pages seeded, embedded and
     * retrieved — is ~3.6s once Tomcat is up, so 90s is roughly 25x headroom even with
     * five IT modules sharing the machine under {@code bin/run-tests.sh --parallel}.
     * It is deliberately not larger: three tests poll independently, so the budget is
     * paid three times over when the dense path is genuinely dead, and this module
     * would become the IT phase's critical path precisely on the runs that are already
     * failing. A real failure should surface fast, not stall the gate.</p>
     */
    private static final long INDEX_BUDGET_MS = 90_000L;

    private static HttpClient http;

    // -----------------------------------------------------------------------
    // Setup
    // -----------------------------------------------------------------------

    /** The embedder is this module's whole reason to exist — fail loudly, do not skip. */
    @BeforeAll
    static void seedCorpusAgainstALiveEmbedder() throws Exception {
        http = HttpClient.newBuilder().cookieHandler( secureCookieOverHttp() ).build();

        HttpResponse< String > tags = null;
        IOException unreachable = null;
        try {
            tags = http.send(
                HttpRequest.newBuilder( URI.create( EMBEDDER + "/api/tags" ) ).GET().build(),
                HttpResponse.BodyHandlers.ofString() );
        } catch ( final IOException e ) {
            // A dead port throws rather than answering, which is the single most likely way
            // this module fails. Convert it into the actionable message instead of letting a
            // bare ConnectException stack trace stand in for the diagnosis.
            unreachable = e;
        }
        assertEquals( 200, tags == null ? -1 : tags.statusCode(),
            "No embedder at " + EMBEDDER + ( unreachable == null ? "" : " (" + unreachable + ")" )
                + ". bin/run-tests.sh starts it; if you invoked "
                + "maven directly, run: COMPOSE_PROJECT_NAME=wikantik-embed-test "
                + "WIKANTIK_EMBEDDING_PORT=11435 docker compose -f "
                + "docker/docker-compose.embeddings.yml up -d" );
        assertTrue( tags.body().contains( "qwen3-embedding:0.6b" ),
            "Embedder at " + EMBEDDER + " does not serve qwen3-embedding:0.6b — the wiki is "
                + "configured for model code qwen3-embedding-0.6b (1024 dims) and every "
                + "embedding call will fail. /api/tags said: " + tags.body() );

        login();
        for ( final Map.Entry< String, String > e : FIXTURES.entrySet() ) {
            writePage( e.getKey(), e.getValue() );
        }
    }

    // -----------------------------------------------------------------------
    // Tests
    // -----------------------------------------------------------------------

    /**
     * Mechanically enforces the property the guard rests on. If someone later reworders
     * either the query or the page and lets a word leak across, this fails before the
     * guard has a chance to pass for the wrong reason.
     */
    @Test
    @Order( 1 )
    void querySharesNoWordWithTheTargetPage() {
        final Set< String > shared = new LinkedHashSet<>( words( SEMANTIC_QUERY ) );
        shared.retainAll( words( TARGET + "\n" + FIXTURES.get( TARGET ) ) );
        assertTrue( shared.isEmpty(),
            "The non-vacuity guard requires zero lexical overlap between the query and '"
                + TARGET + "' — a shared term would let BM25 satisfy the guard without any "
                + "embedding. Shared words: " + shared );
    }

    /** A bundle is assembled, and every section carries a well-formed version-pinned citation. */
    @Test
    @Order( 2 )
    void bundleReturnsCitedSections() throws Exception {
        final JsonObject bundle = awaitBundleContaining( SHAPE_QUERY, "DenseProbeApiary" );
        final JsonArray sections = bundle.getAsJsonArray( "sections" );
        assertFalse( sections.isEmpty(), "expected at least one section: " + bundle );

        // GET /api/bundle serialises ContextBundle directly with Gson — there is NO
        // "data" envelope. Shape: { query, sections[], coverage }, and each section is
        // BundleSection { canonicalId, slug, headingPath, text, score, citation }.
        assertEquals( SHAPE_QUERY, bundle.get( "query" ).getAsString(), "echoed query" );
        assertTrue( bundle.has( "coverage" ), "bundle must carry a coverage signal: " + bundle );

        for ( final JsonElement el : sections ) {
            final JsonObject s = el.getAsJsonObject();
            final String slug = str( s, "slug" );
            assertFalse( slug == null || slug.isBlank(), "section must name its source page: " + s );
            assertNotNull( str( s, "canonicalId" ), "section must carry a canonicalId: " + s );

            final JsonObject cite = s.getAsJsonObject( "citation" );
            assertNotNull( cite, "section must carry a citation: " + s );
            assertEquals( str( s, "canonicalId" ), str( cite, "canonicalId" ),
                "citation must be pinned to the section's own page: " + s );
            assertTrue( cite.get( "version" ).getAsInt() >= 1,
                "citation must pin a real page version: " + cite );
            // The citation is span-hashed (ADR-0005): the digest must actually be the
            // digest of the text handed back, or the handle cannot detect drift.
            assertEquals( sha256( str( s, "text" ) ), str( cite, "spanSha256" ),
                "spanSha256 must be the SHA-256 of the cited span: " + cite );
        }
    }

    /**
     * NON-VACUITY GUARD. {@link #SEMANTIC_QUERY} shares no word with {@link #TARGET}
     * (asserted in {@link #querySharesNoWordWithTheTargetPage()}), so the lexical ranker
     * has nothing to score and cannot produce this result. The target must nevertheless
     * come back <em>ranked first</em>, ahead of four unrelated fixture pages. If this
     * passes with the embedder stopped, the guard is broken and the suite is worthless.
     */
    @Test
    @Order( 3 )
    void bundleFindsASectionNoLexicalSearchCouldFind() throws Exception {
        // Waits on the rank-1 condition, not mere presence: polling for presence and then
        // asserting rank-1 lets the loop return in a transient partially-indexed state
        // (target indexed, a fixture that outranks it not yet), turning a timing artifact
        // into a semantic-looking failure.
        final JsonObject bundle = awaitBundleRankedFirst( SEMANTIC_QUERY, TARGET );
        final JsonArray sections = bundle.getAsJsonArray( "sections" );
        assertFalse( sections.isEmpty(),
            "A semantically-related query returned nothing — dense retrieval is not "
                + "contributing, so the bundle is running BM25-only: " + bundle );
        assertEquals( TARGET, str( sections.get( 0 ).getAsJsonObject(), "slug" ),
            "'" + SEMANTIC_QUERY + "' shares no word with '" + TARGET + "', so only an "
                + "embedding can rank it first. Ranking was " + slugs( sections ) );
    }

    /**
     * The same claim stated directly against the retrieval internals: {@code ?debug=rankings}
     * returns the raw dense and BM25 chunk rankings behind the bundle. A non-empty dense
     * ranking is only possible when the query embedded successfully AND the vector index
     * holds the freshly-embedded chunks, so this pinpoints a dead embedder in one assertion
     * instead of leaving it to be inferred from a ranking that came out wrong.
     */
    @Test
    @Order( 4 )
    void denseRankerContributesCandidates() throws Exception {
        awaitBundleContaining( SEMANTIC_QUERY, TARGET );   // ensure the pipeline has caught up

        final HttpResponse< String > r = get( "/api/bundle?debug=rankings&k=20&q="
            + URLEncoder.encode( SEMANTIC_QUERY, StandardCharsets.UTF_8 ) );
        assertEquals( 200, r.statusCode(), "GET /api/bundle?debug=rankings: " + r.body() );

        final JsonObject rankings = JsonParser.parseString( r.body() ).getAsJsonObject();
        final JsonArray dense = rankings.getAsJsonArray( "dense" );
        assertFalse( dense == null || dense.isEmpty(),
            "The dense ranker returned no candidates. Either the query failed to embed "
                + "(embedder at " + EMBEDDER + " down or serving the wrong model) or the "
                + "Lucene HNSW index never received the seeded chunks: " + r.body() );
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Polls until {@code expectedSlug} appears anywhere among the returned sections. */
    private static JsonObject awaitBundleContaining( final String query, final String expectedSlug )
            throws Exception {
        return awaitBundle( query, ranking -> ranking.contains( expectedSlug ),
            "'" + expectedSlug + "' never appeared in the bundle" );
    }

    /** Polls until {@code expectedSlug} is the <em>top-ranked</em> section. */
    private static JsonObject awaitBundleRankedFirst( final String query, final String expectedSlug )
            throws Exception {
        return awaitBundle( query, ranking -> !ranking.isEmpty() && expectedSlug.equals( ranking.get( 0 ) ),
            "'" + expectedSlug + "' never reached rank 1 in the bundle" );
    }

    /**
     * Polls {@code GET /api/bundle} until {@code ready} accepts the section ranking, or the
     * budget expires. The chunk → embed → index pipeline is asynchronous by design, so an
     * empty first response is normal rather than a failure.
     *
     * <p>Callers pass the predicate they are about to assert on, so the wait condition and
     * the assertion can never disagree — a poll that returns on a weaker condition than the
     * one being asserted is a flake generator.</p>
     */
    private static JsonObject awaitBundle( final String query,
                                           final Predicate< List< String > > ready,
                                           final String failureLead ) throws Exception {
        final long deadline = System.currentTimeMillis() + INDEX_BUDGET_MS;
        JsonObject last = null;
        while ( System.currentTimeMillis() < deadline ) {
            last = bundle( query );
            if ( ready.test( slugs( last.getAsJsonArray( "sections" ) ) ) ) {
                return last;
            }
            Thread.sleep( 2_000L );
        }
        fail( failureLead + " for '" + query + "' within " + ( INDEX_BUDGET_MS / 1000 )
            + "s. Last response: " + last );
        return null;   // unreachable
    }

    private static JsonObject bundle( final String query ) throws Exception {
        final HttpResponse< String > r = get( "/api/bundle?q="
            + URLEncoder.encode( query, StandardCharsets.UTF_8 ) );
        assertEquals( 200, r.statusCode(), "GET /api/bundle: " + r.body() );
        return JsonParser.parseString( r.body() ).getAsJsonObject();
    }

    private static HttpResponse< String > get( final String path )
            throws IOException, InterruptedException {
        return http.send(
            HttpRequest.newBuilder( URI.create( BASE + path ) )
                .header( "Accept", "application/json" ).GET().build(),
            HttpResponse.BodyHandlers.ofString() );
    }

    /** Authenticates the shared cookie jar; page creation goes through the normal save path. */
    private static void login() throws IOException, InterruptedException {
        final HttpResponse< String > resp = http.send(
            HttpRequest.newBuilder( URI.create( BASE + "/api/auth/login" ) )
                .header( "Content-Type", "application/json" )
                .header( "Accept", "application/json" )
                .POST( HttpRequest.BodyPublishers.ofString(
                    "{\"username\":\"janne\",\"password\":\"myP@5sw0rd\"}" ) )
                .build(),
            HttpResponse.BodyHandlers.ofString() );
        assertEquals( 200, resp.statusCode(), "login should succeed: " + resp.body() );
    }

    private static void writePage( final String name, final String markdown )
            throws IOException, InterruptedException {
        final HttpResponse< String > resp = http.send(
            HttpRequest.newBuilder( URI.create( BASE + "/api/pages/" + name ) )
                .header( "Content-Type", "application/json" )
                .header( "Accept", "application/json" )
                .PUT( HttpRequest.BodyPublishers.ofString(
                    "{\"content\":" + jsonString( markdown ) + "}" ) )
                .build(),
            HttpResponse.BodyHandlers.ofString() );
        assertEquals( 200, resp.statusCode(),
            "seeding '" + name + "' must succeed — the whole suite reads from it: " + resp.body() );
    }

    /** Slugs of the given sections, in bundle rank order. */
    private static List< String > slugs( final JsonArray sections ) {
        final List< String > out = new ArrayList<>();
        if ( sections == null ) return out;
        for ( final JsonElement el : sections ) {
            out.add( str( el.getAsJsonObject(), "slug" ) );
        }
        return out;
    }

    private static String str( final JsonObject o, final String field ) {
        final JsonElement el = o == null ? null : o.get( field );
        return el == null || el.isJsonNull() ? null : el.getAsString();
    }

    /**
     * Lower-cased maximal letter runs. Deliberately keeps stop words: Lucene's
     * StandardAnalyzer drops some of them, so requiring an empty intersection over the
     * unfiltered token set is strictly stronger than the analyzer's own view.
     */
    private static Set< String > words( final String text ) {
        final Set< String > out = new LinkedHashSet<>();
        for ( final String w : text.toLowerCase( Locale.ROOT ).split( "[^a-z]+" ) ) {
            if ( !w.isEmpty() ) out.add( w );
        }
        return out;
    }

    private static String sha256( final String text ) throws Exception {
        final byte[] d = MessageDigest.getInstance( "SHA-256" )
            .digest( text.getBytes( StandardCharsets.UTF_8 ) );
        final StringBuilder sb = new StringBuilder( d.length * 2 );
        for ( final byte b : d ) sb.append( String.format( "%02x", b ) );
        return sb.toString();
    }

    private static String jsonString( final String s ) {
        final StringBuilder sb = new StringBuilder( s.length() + 16 ).append( '"' );
        for ( int i = 0; i < s.length(); i++ ) {
            final char c = s.charAt( i );
            switch ( c ) {
                case '"'  -> sb.append( "\\\"" );
                case '\\' -> sb.append( "\\\\" );
                case '\n' -> sb.append( "\\n" );
                case '\r' -> sb.append( "\\r" );
                case '\t' -> sb.append( "\\t" );
                default   -> {
                    if ( c < 0x20 ) sb.append( String.format( "\\u%04x", (int) c ) );
                    else sb.append( c );
                }
            }
        }
        return sb.append( '"' ).toString();
    }

    /**
     * The wiki sets its session cookie with {@code Secure}, which java.net's cookie manager
     * refuses to return over plain http — the Cargo IT deployment is http-only, so the jar
     * is keyed on an https view of each URI. Same shim as {@code FrontmatterSaveContractIT}.
     */
    private static CookieHandler secureCookieOverHttp() {
        final CookieManager cm = new CookieManager( null, CookiePolicy.ACCEPT_ALL );
        return new CookieHandler() {
            @Override
            public Map< String, List< String > > get( final URI uri,
                    final Map< String, List< String > > requestHeaders ) throws IOException {
                return cm.get( asHttps( uri ), requestHeaders );
            }

            @Override
            public void put( final URI uri, final Map< String, List< String > > responseHeaders )
                    throws IOException {
                cm.put( uri, responseHeaders );
            }

            private URI asHttps( final URI uri ) {
                return URI.create( uri.toString().replaceFirst( "^http:", "https:" ) );
            }
        };
    }
}
