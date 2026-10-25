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
package com.wikantik.architecture;

import com.wikantik.plantuml.PlantUmlEngine;
import com.wikantik.plantuml.RenderOutcome;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Licence gate for the PlantUML dependency. PlantUML itself is MIT, but it bundles Ditaa (LGPL), which must
 * never ship; the {@code wikantik-plantuml} module shades PlantUML with Ditaa stripped. This guards that
 * the WAR sees exactly that shaded copy and nothing else.
 */
class PlantUmlLicenseArchTest {

    private static final String DITAA_CLASS = "org/stathissideris/ascii2image/core/ConversionOptions.class";
    private static final String PLANTUML_MARKER = "net/sourceforge/plantuml/SourceStringReader.class";

    @Test
    void noDitaaOnRuntimeClasspath() {
        assertNull( getClass().getClassLoader().getResource( "org/stathissideris" ),
                    "Ditaa (LGPL) classes must be stripped from the shaded PlantUML jar" );
        // The directory lookup above is null for jars without directory entries, so also probe real class files.
        assertNull( getClass().getClassLoader().getResource( DITAA_CLASS ),
                    "Ditaa (LGPL) class files must be stripped from the shaded PlantUML jar" );
    }

    @Test
    void plantUmlPresent() {
        assertNotNull( getClass().getClassLoader().getResource( PLANTUML_MARKER ) );
    }

    /**
     * Surefire runs on a manifest-only jar, so {@code java.class.path} cannot be inspected; instead every copy
     * of PlantUML the class loader can see must live inside a Wikantik artifact (the shaded
     * {@code wikantik-plantuml} jar, or the test-scope {@code wikantik-extract-cli} fat jar built from it),
     * never inside the unshaded {@code plantuml-mit} jar, and the shaded jar must be among them.
     */
    @Test
    void noUnshadedPlantUmlJar() throws IOException {
        final List< URL > urls = Collections.list( getClass().getClassLoader().getResources( PLANTUML_MARKER ) );
        assertTrue( urls.stream().anyMatch( u -> u.toString().contains( "/wikantik-plantuml" ) ),
                    "PlantUML must come from the shaded wikantik-plantuml jar: " + urls );
        for( final URL u : urls ) {
            assertTrue( u.toString().contains( "/wikantik-plantuml" ) || u.toString().contains( "/wikantik-extract-cli" ),
                        "PlantUML must never load from the unshaded plantuml-mit jar: " + u );
        }
    }

    /** @return the text of {@code resource} as packaged inside the shaded wikantik-plantuml jar */
    private String fromShadedJar( final String resource ) throws IOException {
        for( final URL u : Collections.list( getClass().getClassLoader().getResources( resource ) ) ) {
            if( u.toString().contains( "/wikantik-plantuml" ) ) {
                try( InputStream in = u.openStream() ) {
                    return new String( in.readAllBytes(), StandardCharsets.UTF_8 );
                }
            }
        }
        throw new AssertionError( resource + " is not packaged in the shaded wikantik-plantuml jar" );
    }

    /**
     * The WAR must carry PlantUML's MIT licence, the EPL-1.0 statement for Smetana with where its source is, and a
     * NOTICE that does not pass the bundled code off as ASF Apache-2.0 code.
     */
    @Test
    void thirdPartyNoticesShipInTheShadedJar() throws IOException {
        final String mit = fromShadedJar( "META-INF/LICENSE-plantuml.txt" );
        assertTrue( mit.contains( "under the terms of the MIT License" ) && mit.contains( "Arnaud Roques" ), mit );
        assertTrue( mit.contains( "Permission is hereby granted, free of charge" ), mit );

        final String notice = fromShadedJar( "META-INF/NOTICE-plantuml.txt" );
        assertTrue( notice.contains( "Eclipse Public License v1.0" ) && notice.contains( "AT&T" ), notice );
        assertTrue( notice.contains( "plantuml-mit-1.2026.8-sources.jar" ), notice );
        assertTrue( notice.contains( "Ditaa" ), notice );

        final String jarNotice = fromShadedJar( "META-INF/NOTICE" );
        assertTrue( jarNotice.contains( "NOT Apache Software Foundation code" ), jarNotice );
        assertTrue( jarNotice.contains( "NOTICE-plantuml.txt" ), jarNotice );
        assertFalse( jarNotice.contains( "Copyright 2001-" ), "the generic ASF NOTICE must not describe the jar: " + jarNotice );
    }

    /** Only the C4 standard library (the one include the policy allows) and the library index ship. */
    @Test
    void onlyTheC4StandardLibraryShips() throws IOException {
        final URL marker = getClass().getClassLoader().getResource( PLANTUML_MARKER );
        assertNotNull( marker );
        assertTrue( marker.toString().startsWith( "jar:" ) && marker.toString().contains( "/wikantik-plantuml" ),
                    "expected the shaded jar, got " + marker );
        final JarURLConnection connection = (JarURLConnection) marker.openConnection();
        connection.setUseCaches( false );
        try( JarFile jar = connection.getJarFile() ) {
            final List< String > stray = jar.stream().map( e -> e.getName() ).filter( n -> n.startsWith( "stdlib/" ) )
                .filter( n -> !n.equals( "stdlib/" ) && !n.equals( "stdlib/home.spm" ) && !n.startsWith( "stdlib/c4/" ) )
                .toList();
            assertEquals( List.of(), stray, "unreachable stdlib icon sets must be stripped" );
            assertNotNull( jar.getEntry( "stdlib/c4/puml.spm" ), "the C4 library must ship" );
        }
    }

    /** The stripped jar still draws what wiki diagrams may use: C4 and PlantUML's built-in themes. */
    @Test
    void c4AndThemesDrawFromTheShippedJar() {
        final RenderOutcome c4 = PlantUmlEngine.render(
            "!include <C4/C4_Container>\nPerson(u, \"User\")\nContainer(web, \"Web\", \"Java\", \"The site\")\nRel(u, web, \"uses\")\n" );
        assertTrue( c4.isDrawn(), () -> "C4: " + c4.reason() + " " + c4.message() );
        final RenderOutcome themed = PlantUmlEngine.render( "!theme cerulean\nclass A\nclass B\nA --> B\n" );
        assertTrue( themed.isDrawn(), () -> "theme: " + themed.reason() + " " + themed.message() );
    }
}
