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
package com.wikantik.knowledge.briefing;

import com.wikantik.knowledge.briefing.BriefingConfig.BriefingLimits;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link BriefingConfig#limits}: every tunable is read from its documented key and falls back to the shipped default. */
class BriefingConfigTest {

    @Test
    void nullPropertiesYieldTheShippedDefaults() {
        assertEquals( BriefingLimits.defaults(), BriefingConfig.limits( null ) );
    }

    @Test
    void emptyPropertiesYieldTheShippedDefaults() {
        assertEquals( BriefingLimits.defaults(), BriefingConfig.limits( new Properties() ) );
    }

    @Test
    void everyKeyIsReadFromItsDocumentedName() {
        final Properties p = new Properties();
        p.setProperty( "wikantik.briefing.default_budget", "1000" );
        p.setProperty( "wikantik.briefing.max_budget", "2000" );
        p.setProperty( "wikantik.briefing.sections_per_page", "2" );
        p.setProperty( "wikantik.briefing.candidate_pool", "10" );
        p.setProperty( "wikantik.briefing.scope.cited.enabled", "false" );
        p.setProperty( "wikantik.briefing.scope.cited.max", "3" );
        p.setProperty( "wikantik.briefing.elsewhere.max", "4" );
        p.setProperty( "wikantik.briefing.pointers.max", "5" );

        assertEquals( new BriefingLimits( 1000, 2000, 2, 10, false, 3, 4, 5 ), BriefingConfig.limits( p ) );
    }

    @Test
    void invalidOrNonPositiveIntegersFallBackToTheDefault() {
        final Properties p = new Properties();
        p.setProperty( "wikantik.briefing.sections_per_page", "lots" );
        p.setProperty( "wikantik.briefing.candidate_pool", "0" );
        p.setProperty( "wikantik.briefing.scope.cited.max", "-3" );
        p.setProperty( "wikantik.briefing.elsewhere.max", "  " );

        assertEquals( BriefingLimits.defaults(), BriefingConfig.limits( p ) );
    }

    /**
     * The shipped {@code ini/wikantik.properties} and the code constants are two statements of the same
     * defaults. The drift gate cannot compare them (the read sites default to a named constant, not a
     * literal), so this pins them: loading the file's own values must reproduce {@code defaults()}.
     */
    @Test
    void shippedIniDeclaresTheCodeDefaults() throws IOException {
        final Properties ini = new Properties();
        // Source path, not the classpath: test resources shadow ini/wikantik.properties on this module's classpath.
        try ( InputStream in = Files.newInputStream( Path.of( "src/main/resources/ini/wikantik.properties" ) ) ) {
            ini.load( in );
        }
        assertEquals( BriefingLimits.defaults(), BriefingConfig.limits( ini ) );
        assertEquals( "true", ini.getProperty( "wikantik.briefing.enabled" ).trim() );
        for ( final String key : new String[] { "default_budget", "max_budget", "sections_per_page", "candidate_pool",
            "scope.cited.enabled", "scope.cited.max", "elsewhere.max", "pointers.max" } ) {
            assertTrue( ini.containsKey( "wikantik.briefing." + key ), key + " must be declared in the ini" );
        }
    }
}
