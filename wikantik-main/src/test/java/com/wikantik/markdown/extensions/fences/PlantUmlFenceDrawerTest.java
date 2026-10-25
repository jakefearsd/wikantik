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
package com.wikantik.markdown.extensions.fences;

import com.wikantik.plantuml.PlantUmlSettings;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The PlantUML drawer reads its settings from the wiki properties and adapts the PlantUML render service. */
class PlantUmlFenceDrawerTest {

    private static Properties props( final String... kv ) {
        final Properties p = new Properties();
        for ( int i = 0; i < kv.length; i += 2 ) {
            p.setProperty( kv[ i ], kv[ i + 1 ] );
        }
        return p;
    }

    @Test
    void defaultsMatchTheShippedSettings() {
        final Properties empty = new Properties();
        assertEquals( PlantUmlSettings.defaults(), PlantUmlFenceDrawer.settings( empty ) );
        assertEquals( 20, PlantUmlFenceDrawer.pageMaxDiagrams( empty ) );
        assertEquals( 10_000L, PlantUmlFenceDrawer.pageBudgetMs( empty ) );
        assertEquals( 8_388_608L, PlantUmlFenceDrawer.pageMaxBytes( empty ) );
        assertEquals( 524_288, PlantUmlFenceDrawer.settings( empty ).maxSvgBytes() );
        assertEquals( 67_108_864L, PlantUmlFenceDrawer.settings( empty ).cacheMaxBytes() );
        final PlantUmlFenceDrawer drawer = PlantUmlFenceDrawer.fromProperties( empty );
        assertEquals( "plantuml", drawer.language() );
        assertEquals( 5_000L, drawer.defaultBudgetMs() );
    }

    @Test
    void everyKeyIsRead() {
        final Properties p = props(
                "wikantik.fences.plantuml.enabled", "false",
                "wikantik.fences.plantuml.threads", "3",
                "wikantik.fences.plantuml.queueCapacity", "4",
                "wikantik.fences.plantuml.threadStackBytes", "1048576",
                "wikantik.fences.plantuml.timeoutMs", "1234",
                "wikantik.fences.plantuml.maxSvgBytes", "4096",
                "wikantik.fences.plantuml.slowRetryMinutes", "2",
                "wikantik.fences.plantuml.cacheEntries", "7",
                "wikantik.fences.plantuml.pageMaxDiagrams", "5",
                "wikantik.fences.plantuml.pageBudgetMs", "999",
                "wikantik.fences.plantuml.cacheMaxBytes", "123456",
                "wikantik.fences.plantuml.pageMaxBytes", "654321" );

        assertEquals( new PlantUmlSettings( false, 3, 4, 1_048_576L, 1234L, 4096, 120_000L, 7, 123_456L ),
                PlantUmlFenceDrawer.settings( p ) );
        assertEquals( 5, PlantUmlFenceDrawer.pageMaxDiagrams( p ) );
        assertEquals( 999L, PlantUmlFenceDrawer.pageBudgetMs( p ) );
        assertEquals( 654_321L, PlantUmlFenceDrawer.pageMaxBytes( p ) );
        assertEquals( 1234L, PlantUmlFenceDrawer.fromProperties( p ).defaultBudgetMs() );
    }

    @Test
    void malformedNumbersFallBackToDefaults() {
        final Properties p = props( "wikantik.fences.plantuml.timeoutMs", "soon", "wikantik.fences.plantuml.pageBudgetMs", " " );
        assertEquals( 5_000L, PlantUmlFenceDrawer.settings( p ).timeoutMs() );
        assertEquals( 10_000L, PlantUmlFenceDrawer.pageBudgetMs( p ) );
    }

    @Test
    void drawsARealDiagramInBothVariants() {
        final ServerDrawing d = PlantUmlFenceDrawer.fromProperties( new Properties() ).draw( "A -> B : hello\n", 30_000L );

        assertTrue( d.isDrawn(), () -> "reason=" + d.reason() + " message=" + d.message() );
        assertTrue( d.lightSvg().contains( "<svg" ), d.lightSvg() );
        assertTrue( d.darkSvg().contains( "<svg" ), d.darkSvg() );
        assertFalse( d.transientFailure() );
    }

    @Test
    void syntaxErrorCarriesItsLine() {
        final ServerDrawing d = PlantUmlFenceDrawer.fromProperties( new Properties() )
                .draw( "class A\nclass B\nclass {\nclass D", 30_000L );

        assertFalse( d.isDrawn() );
        assertEquals( "syntax-error", d.reason() );
        assertEquals( 3, d.line() );
        assertFalse( d.transientFailure() );
    }

    @Test
    void policyRefusalIsReported() {
        final ServerDrawing d = PlantUmlFenceDrawer.fromProperties( new Properties() ).draw( "!include x.puml\nA -> B\n", 30_000L );
        assertEquals( "include", d.reason() );
    }

    @Test
    void killSwitchRefusesEveryFence() {
        final ServerDrawing d = PlantUmlFenceDrawer.fromProperties( props( "wikantik.fences.plantuml.enabled", "false" ) )
                .draw( "A -> B\n", 30_000L );

        assertFalse( d.isDrawn() );
        assertEquals( "plantuml-disabled", d.reason() );
        assertFalse( d.transientFailure() );
    }

    @Test
    void failedSetupRefusesEveryFenceAsRenderFailedNotPageBudget() {
        final ServerFenceResolver r = PlantUmlFenceDrawer.resolver( new Properties(), p -> {
            throw new IllegalStateException( "PlantUML missing" );
        } );
        final String html = ServerFenceResolverTest.render(
                "```plantuml\nA -> B\n```\n\n```plantuml\nC -> D\n```\n\n```plantuml\nE -> F\n```\n", false );

        final Matcher m = Pattern.compile( "data-fence-error=\"([^\"]*)\"" ).matcher( r.resolve( html ) );
        final List< String > reasons = new ArrayList<>();
        while ( m.find() ) {
            reasons.add( m.group( 1 ) );
        }
        assertEquals( List.of( "render-failed", "render-failed", "render-failed" ), reasons );
    }

    @Test
    void unavailableDrawerRefusesAsRenderFailed() {
        final ServerDrawing d = PlantUmlFenceDrawer.unavailable().draw( "A -> B\n", 30_000L );

        assertFalse( d.isDrawn() );
        assertEquals( "render-failed", d.reason() );
        assertTrue( d.transientFailure() );
    }
}
