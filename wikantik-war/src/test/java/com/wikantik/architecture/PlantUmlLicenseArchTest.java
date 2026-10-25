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

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URL;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Licence gate for the PlantUML dependency. PlantUML itself is MIT, but it bundles Ditaa (LGPL), which must
 * never ship; the {@code wikantik-plantuml} module shades PlantUML with Ditaa stripped. This guards that
 * the WAR sees exactly that shaded copy and nothing else.
 */
class PlantUmlLicenseArchTest {

    private static final String PLANTUML_MARKER = "net/sourceforge/plantuml/SourceStringReader.class";

    @Test
    void noDitaaOnRuntimeClasspath() {
        assertNull( getClass().getClassLoader().getResource( "org/stathissideris" ),
                    "Ditaa (LGPL) classes must be stripped from the shaded PlantUML jar" );
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
}
