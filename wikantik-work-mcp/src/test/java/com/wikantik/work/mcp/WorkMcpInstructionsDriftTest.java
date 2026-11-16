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
package com.wikantik.work.mcp;

import com.wikantik.work.WorkDirectory;
import com.wikantik.work.WorkService;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * The instructions text goes to every client in the MCP {@code initialize} reply. It must name every tool this
 * endpoint registers, and no tool it does not (the knowledge endpoint's tools it deliberately points at are listed in
 * {@link #OTHER_ENDPOINTS}).
 */
class WorkMcpInstructionsDriftTest {

    /** Tools of the other endpoints the instructions send an agent to; never registered here. */
    private static final Set< String > OTHER_ENDPOINTS = Set.of( "get_briefing" );

    private static final Pattern TOOL_LIKE =
        Pattern.compile( "\\b(?:get|list|claim|update|comment|handoff|release|create)_[a-z][a-z_]*[a-z]\\b" );

    private static String instructions() throws IOException {
        try ( InputStream in = WorkMcpInitializer.class.getResourceAsStream( "/wikantik-work-mcp-instructions.txt" ) ) {
            return new String( in.readAllBytes(), StandardCharsets.UTF_8 );
        }
    }

    private static Set< String > registered() {
        final Set< String > names = new LinkedHashSet<>();
        WorkMcpInitializer.assembleTools( mock( WorkService.class ), mock( WorkDirectory.class ), Duration.ofMinutes( 30 ) )
            .forEach( t -> names.add( t.name() ) );
        return names;
    }

    private static Set< String > mentioned( final String text ) {
        final Set< String > found = new LinkedHashSet<>();
        final Matcher m = TOOL_LIKE.matcher( text );
        while ( m.find() ) {
            found.add( m.group() );
        }
        found.removeAll( OTHER_ENDPOINTS );
        return found;
    }

    @Test
    void instructionsNameEveryTool() throws IOException {
        final String text = instructions();
        for ( final String tool : registered() ) {
            assertTrue( Pattern.compile( "\\b" + tool + "\\b" ).matcher( text ).find(),
                "the instructions never name the registered tool " + tool );
        }
    }

    @Test
    void instructionsNameNoUnregisteredTool() throws IOException {
        final Set< String > extra = mentioned( instructions() );
        extra.removeAll( registered() );
        assertEquals( Set.of(), extra, "the instructions advertise tools this endpoint does not register" );
    }

    @Test
    void toolNamesAreTheExpectedSetForThisTask() {
        assertEquals( Set.of( "get_work_context", "list_claimable", "get_item" ), registered() );
    }

    @Test
    void instructionsTreatItemTextAsData() throws IOException {
        final String text = instructions().toLowerCase( java.util.Locale.ROOT );
        assertTrue( text.contains( "data" ) && text.contains( "not instructions" ), "item text is data, not instructions" );
        assertTrue( text.contains( "get_briefing" ) && text.contains( "wikicluster" ), "points at the knowledge briefing" );
    }
}
