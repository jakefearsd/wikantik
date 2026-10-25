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
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlantUmlSandboxTest {

    @Test
    void sandboxActive() {
        assertTrue( PlantUmlSandbox.ensure() );
    }

    /**
     * The included file is valid PlantUML, so outside the sandbox PlantUML would include it and draw its class: only the
     * sandbox turns the include into PlantUML's own "cannot include" error, reported on the author's line 1.
     */
    @Test
    void includeOfLocalFileFails( @TempDir final Path dir ) throws IOException {
        final Path leaked = dir.resolve( "leak.puml" );
        Files.writeString( leaked, "class LeakedFromDisk\n", StandardCharsets.UTF_8 );

        final RenderOutcome out = PlantUmlEngine.render( "!include " + leaked + "\nclass A" );

        assertFalse( out.isDrawn(), "the include was followed: the file's class was drawn" );
        assertEquals( "syntax-error", out.reason() );
        assertTrue( out.message().contains( "cannot include" ), out.message() );
        assertFalse( out.message().contains( "LeakedFromDisk" ), out.message() );
        assertEquals( 1, out.line() );
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
