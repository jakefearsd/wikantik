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
package com.wikantik.api.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Properties;

import org.junit.jupiter.api.Test;

class KnowledgeGraphFlagTest {

    private static Properties with( final String value ) {
        final Properties p = new Properties();
        p.setProperty( KnowledgeGraphFlag.PROPERTY, value );
        return p;
    }

    @Test
    void keyNameIsStable() {
        assertEquals( "wikantik.knowledge.enabled", KnowledgeGraphFlag.PROPERTY );
    }

    @Test
    void defaultsToOff() {
        assertFalse( KnowledgeGraphFlag.DEFAULT );
        assertFalse( KnowledgeGraphFlag.isEnabled( new Properties() ) );
    }

    @Test
    void nullPropertiesAreOff() {
        assertFalse( KnowledgeGraphFlag.isEnabled( null ) );
    }

    @Test
    void blankValueFallsBackToDefault() {
        assertFalse( KnowledgeGraphFlag.isEnabled( with( "  " ) ) );
        assertFalse( KnowledgeGraphFlag.isEnabled( with( "" ) ) );
    }

    @Test
    void explicitValuesWin() {
        assertTrue( KnowledgeGraphFlag.isEnabled( with( "true" ) ) );
        assertTrue( KnowledgeGraphFlag.isEnabled( with( " TRUE " ) ) );
        assertFalse( KnowledgeGraphFlag.isEnabled( with( "false" ) ) );
    }
}
