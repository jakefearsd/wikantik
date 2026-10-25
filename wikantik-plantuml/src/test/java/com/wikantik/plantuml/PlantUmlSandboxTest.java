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
package com.wikantik.plantuml;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlantUmlSandboxTest {

    @Test
    void sandboxActive() {
        assertTrue( PlantUmlSandbox.ensure() );
    }

    @Test
    void includeOfLocalFileFails() {
        final RenderOutcome out = PlantUmlEngine.render( "!include /etc/hostname\nclass A" );
        assertFalse( out.isDrawn() );
        assertEquals( "syntax-error", out.reason() );
    }

    @Test
    void getenvIsEmpty() {
        final String home = System.getenv( "HOME" );
        assertNotNull( home );
        final RenderOutcome out = PlantUmlEngine.render( "title Home=[%getenv(\"HOME\")]\nclass A" );
        assertTrue( out.isDrawn(), () -> "reason=" + out.reason() + " msg=" + out.message() );
        assertFalse( out.drawing().lightSvg().contains( home ) );
        assertFalse( out.drawing().darkSvg().contains( home ) );
    }
}
