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
package com.wikantik.rest;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.wikantik.HttpMockFactory;
import com.wikantik.TestEngine;
import com.wikantik.api.config.GenAiMode;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies {@code GET /api/capabilities} reports each optional-subsystem flag
 * independently of the others (no cross-flag interaction assertions here —
 * mode-ceiling logic across flags is covered separately at the phase gate),
 * defaults every field to enabled/"full" when its property is entirely
 * absent, and requires no authentication.
 */
class CapabilitiesResourceTest {

    private TestEngine engine;
    private CapabilitiesResource servlet;
    private final Gson gson = new Gson();

    @BeforeEach
    void setUp() throws Exception {
        final Properties props = TestEngine.getTestProperties();
        engine = new TestEngine( props );

        servlet = new CapabilitiesResource();
        final ServletConfig config = Mockito.mock( ServletConfig.class );
        Mockito.doReturn( engine.getServletContext() ).when( config ).getServletContext();
        servlet.init( config );
    }

    @AfterEach
    void tearDown() {
        if ( engine != null ) engine.stop();
    }

    @Test
    void testDefaultsAllTrueAndGenaiModeFullWhenPropertiesAbsent() throws Exception {
        engine.getWikiProperties().remove( "wikantik.knowledge.enabled" );
        engine.getWikiProperties().remove( "wikantik.search.hybrid.enabled" );
        engine.getWikiProperties().remove( "wikantik.genai.mode" );
        engine.getWikiProperties().remove( "wikantik.ontology.enabled" );
        engine.getWikiProperties().remove( "wikantik.connectors.enabled" );
        engine.getWikiProperties().remove( "wikantik.citations.enabled" );

        final JsonObject obj = doGetAsJson();

        assertTrue( obj.get( "knowledgeGraph" ).getAsBoolean() );
        assertTrue( obj.get( "hybridSearch" ).getAsBoolean() );
        assertEquals( "full", obj.get( "genaiMode" ).getAsString() );
        assertTrue( obj.get( "ontology" ).getAsBoolean() );
        assertTrue( obj.get( "connectors" ).getAsBoolean() );
        assertTrue( obj.get( "citations" ).getAsBoolean() );
    }

    @Test
    void testKnowledgeGraphFalseWhenFlagOff() throws Exception {
        engine.getWikiProperties().setProperty( "wikantik.knowledge.enabled", "false" );
        assertFalse( doGetAsJson().get( "knowledgeGraph" ).getAsBoolean() );
    }

    @Test
    void testKnowledgeGraphTrueWhenFlagOn() throws Exception {
        engine.getWikiProperties().setProperty( "wikantik.knowledge.enabled", "true" );
        assertTrue( doGetAsJson().get( "knowledgeGraph" ).getAsBoolean() );
    }

    @Test
    void testHybridSearchFalseWhenFlagOff() throws Exception {
        engine.getWikiProperties().setProperty( "wikantik.search.hybrid.enabled", "false" );
        assertFalse( doGetAsJson().get( "hybridSearch" ).getAsBoolean() );
    }

    @Test
    void testHybridSearchTrueWhenFlagOn() throws Exception {
        engine.getWikiProperties().setProperty( "wikantik.search.hybrid.enabled", "true" );
        assertTrue( doGetAsJson().get( "hybridSearch" ).getAsBoolean() );
    }

    // ----- genai.mode ceiling interaction (mirrors EmbeddingConfig.fromProperties,
    //       which ANDs the raw flag with GenAiMode.allowsEmbeddings()) -----
    //
    // Sourcing choice, documented per the task brief: the endpoint reads the raw
    // wikantik.search.hybrid.enabled property with an explicit default of TRUE and
    // ANDs in GenAiMode.allowsEmbeddings(), rather than delegating to
    // EmbeddingConfig.fromProperties(props).enabled(). EmbeddingConfig's CODE
    // default for the raw flag is false (master kill-switch semantics for the
    // embedding client), but the SHIPPED ini sets it true — at runtime the engine
    // properties include the ini value so both approaches agree, but this
    // endpoint must also stay truthful for a bare/empty Properties object
    // (defaults-all-true contract above), which delegation would silently flip.

    @Test
    void testHybridSearchFalseWhenGenaiModeNone() throws Exception {
        // Hybrid flag left at its default (true) — the mode ceiling alone forces false.
        engine.getWikiProperties().remove( "wikantik.search.hybrid.enabled" );
        engine.getWikiProperties().setProperty( "wikantik.genai.mode", "none" );
        assertFalse( doGetAsJson().get( "hybridSearch" ).getAsBoolean() );
    }

    @Test
    void testHybridSearchFalseWhenGenaiModeNoneEvenIfFlagExplicitlyOn() throws Exception {
        engine.getWikiProperties().setProperty( "wikantik.search.hybrid.enabled", "true" );
        engine.getWikiProperties().setProperty( "wikantik.genai.mode", "none" );
        assertFalse( doGetAsJson().get( "hybridSearch" ).getAsBoolean() );
    }

    @Test
    void testHybridSearchTrueWhenGenaiModeEmbeddingsOnly() throws Exception {
        // EMBEDDINGS_ONLY allows embeddings (retrieval) — only NONE turns them off.
        engine.getWikiProperties().setProperty( "wikantik.search.hybrid.enabled", "true" );
        engine.getWikiProperties().setProperty( "wikantik.genai.mode", "embeddings-only" );
        assertTrue( doGetAsJson().get( "hybridSearch" ).getAsBoolean() );
    }

    @Test
    void testHybridSearchFalseWhenFlagOffRegardlessOfMode() throws Exception {
        engine.getWikiProperties().setProperty( "wikantik.search.hybrid.enabled", "false" );
        engine.getWikiProperties().setProperty( "wikantik.genai.mode", "full" );
        assertFalse( doGetAsJson().get( "hybridSearch" ).getAsBoolean() );
    }

    @Test
    void testGenaiModeEmbeddingsOnly() throws Exception {
        engine.getWikiProperties().setProperty( "wikantik.genai.mode", "embeddings-only" );
        assertEquals( "embeddings-only", doGetAsJson().get( "genaiMode" ).getAsString() );
    }

    @Test
    void testGenaiModeNone() throws Exception {
        engine.getWikiProperties().setProperty( "wikantik.genai.mode", "none" );
        assertEquals( "none", doGetAsJson().get( "genaiMode" ).getAsString() );
    }

    @Test
    void testGenaiModeFull() throws Exception {
        engine.getWikiProperties().setProperty( "wikantik.genai.mode", "full" );
        assertEquals( "full", doGetAsJson().get( "genaiMode" ).getAsString() );
    }

    @Test
    void testOntologyFalseWhenFlagOff() throws Exception {
        engine.getWikiProperties().setProperty( "wikantik.ontology.enabled", "false" );
        assertFalse( doGetAsJson().get( "ontology" ).getAsBoolean() );
    }

    @Test
    void testConnectorsFalseWhenFlagOff() throws Exception {
        engine.getWikiProperties().setProperty( "wikantik.connectors.enabled", "false" );
        assertFalse( doGetAsJson().get( "connectors" ).getAsBoolean() );
    }

    @Test
    void testCitationsFalseWhenFlagOff() throws Exception {
        engine.getWikiProperties().setProperty( "wikantik.citations.enabled", "false" );
        assertFalse( doGetAsJson().get( "citations" ).getAsBoolean() );
    }

    @Test
    void testToTokenMapsAllEnumValues() {
        assertEquals( "full", CapabilitiesResource.toToken( GenAiMode.FULL ) );
        assertEquals( "embeddings-only", CapabilitiesResource.toToken( GenAiMode.EMBEDDINGS_ONLY ) );
        assertEquals( "none", CapabilitiesResource.toToken( GenAiMode.NONE ) );
    }

    @Test
    void testEndpointRequiresNoAuthentication() throws Exception {
        // No session/principal is set up on the mock request at all — the
        // servlet must still answer 200 with a full JSON body.
        final JsonObject obj = doGetAsJson();
        assertTrue( obj.has( "knowledgeGraph" ) );
    }

    // ----- helpers -----

    private JsonObject doGetAsJson() throws Exception {
        return gson.fromJson( doGet(), JsonObject.class );
    }

    private String doGet() throws Exception {
        final HttpServletRequest req = HttpMockFactory.createHttpRequest( "/api/capabilities" );
        final HttpServletResponse resp = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( resp ).getWriter();

        servlet.doGet( req, resp );
        return sw.toString();
    }
}
