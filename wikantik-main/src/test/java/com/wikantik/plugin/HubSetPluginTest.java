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
package com.wikantik.plugin;

import com.wikantik.TestEngine;
import com.wikantik.api.core.Context;
import com.wikantik.api.frontmatter.FrontmatterWriter;
import com.wikantik.api.spi.Wiki;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.wikantik.TestEngine.with;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link HubSetPlugin}.
 */
class HubSetPluginTest {

    private TestEngine engine;
    private HubSetPlugin plugin;
    private final List< String > createdPages = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        engine = TestEngine.build(
            with( "wikantik.cache.enable", "false" ),
            with( "wikantik.breakTitleWithSpaces", "false" ) );
        plugin = new HubSetPlugin();
        engine.saveText( "CurrentPage", "Test page." );
        createdPages.add( "CurrentPage" );
    }

    @AfterEach
    void tearDown() {
        createdPages.forEach( engine::deleteTestPage );
        createdPages.clear();
        engine.stop();
    }

    private void savePage( final String name, final String content ) throws Exception {
        engine.saveText( name, content );
        createdPages.add( name );
    }

    private Context createContext() {
        return Wiki.context().create( engine, Wiki.contents().page( engine, "CurrentPage" ) );
    }

    @Test
    void linksMode_rendersPageLinks() throws Exception {
        final String hubContent = FrontmatterWriter.write(
            Map.of( "type", "hub", "related", List.of( "Java", "Python", "Kotlin" ) ),
            "Hub body" );
        savePage( "TechHub", hubContent );
        savePage( "Java", "Java page." );
        savePage( "Python", "Python page." );
        savePage( "Kotlin", "Kotlin page." );

        final Context context = createContext();
        final String result = plugin.execute( context, Map.of( "hub", "TechHub" ) );

        assertTrue( result.contains( "Java" ), "Expected Java in result: " + result );
        assertTrue( result.contains( "Python" ), "Expected Python in result: " + result );
        assertTrue( result.contains( "Kotlin" ), "Expected Kotlin in result: " + result );
    }

    @Test
    void emptyHub_returnsMessage() throws Exception {
        final String hubContent = FrontmatterWriter.write(
            Map.of( "type", "hub", "related", List.of() ),
            "" );
        savePage( "EmptyHub", hubContent );

        final Context context = createContext();
        final String result = plugin.execute( context, Map.of( "hub", "EmptyHub" ) );

        assertTrue( result.contains( "no member pages" ), "Expected 'no member pages' in result: " + result );
        assertTrue( result.contains( "hub-set-empty" ), "Expected 'hub-set-empty' in result: " + result );
    }

    @Test
    void nonExistentHub_returnsError() throws Exception {
        final Context context = createContext();
        final String result = plugin.execute( context, Map.of( "hub", "NoSuchHub" ) );

        assertTrue( result.contains( "does not exist" ) || result.contains( "not found" ),
            "Expected 'does not exist' or 'not found' in result: " + result );
    }

    @Test
    void maxParam_limitsOutput() throws Exception {
        final String hubContent = FrontmatterWriter.write(
            Map.of( "type", "hub", "related", List.of( "A", "B", "C", "D", "E" ) ),
            "" );
        savePage( "BigHub", hubContent );
        for ( final String name : List.of( "A", "B", "C", "D", "E" ) ) {
            savePage( name, name + " page." );
        }

        final Context context = createContext();
        final String result = plugin.execute( context, Map.of( "hub", "BigHub", "max", "2" ) );

        // Count occurrences of '(' as a proxy for link count in Markdown [text](url) format
        final long linkCount = result.chars().filter( c -> c == '(' ).count();
        assertTrue( linkCount <= 2, "Should have at most 2 links, found " + linkCount + " in: " + result );
    }

    @Test
    void missingHubParam_returnsError() throws Exception {
        final Context context = createContext();
        final String result = plugin.execute( context, Map.of() );

        assertTrue( result.contains( "error" ) || result.contains( "hub" ),
            "Expected error about missing hub param: " + result );
    }

    @Test
    void nonHubTypePage_returnsError() throws Exception {
        final String content = FrontmatterWriter.write(
            Map.of( "type", "article" ),
            "Not a hub page." );
        savePage( "ArticlePage", content );

        final Context context = createContext();
        final String result = plugin.execute( context, Map.of( "hub", "ArticlePage" ) );

        assertTrue( result.contains( "error" ) || result.contains( "not a Hub" ) || result.contains( "type != hub" ),
            "Expected error for non-hub type page: " + result );
    }

    @Test
    void cardsMode_rendersCardLayout() throws Exception {
        final String hubContent = FrontmatterWriter.write(
            Map.of( "type", "hub", "related", List.of( "CardPageA", "CardPageB" ) ),
            "" );
        savePage( "CardHub", hubContent );

        final String pageA = FrontmatterWriter.write(
            Map.of( "title", "CardPageA", "summary", "About A", "tags", List.of( "alpha" ) ),
            "" );
        savePage( "CardPageA", pageA );

        final String pageB = FrontmatterWriter.write(
            Map.of( "title", "CardPageB", "summary", "About B", "tags", List.of( "beta" ) ),
            "" );
        savePage( "CardPageB", pageB );

        final Context context = createContext();
        final String result = plugin.execute( context, Map.of( "hub", "CardHub", "detail", "cards" ) );

        assertTrue( result.contains( "hub-set-cards" ), "Expected 'hub-set-cards' in result: " + result );
        assertTrue( result.contains( "CardPageA" ), "Expected CardPageA in result: " + result );
        assertTrue( result.contains( "CardPageB" ), "Expected CardPageB in result: " + result );
        assertTrue( result.contains( "About A" ), "Expected summary 'About A' in result: " + result );
    }
}
