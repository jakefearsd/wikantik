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

import com.wikantik.work.Actor;
import com.wikantik.work.WorkDirectory;
import com.wikantik.work.WorkEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/** How an event's actor is shown to an agent. */
class WorkMcpJsonTest {

    private static WorkEvent merged( final String actor ) {
        return new WorkEvent( 1, 1, 7L, "PLAT-1", Instant.parse( "2026-01-01T00:00:00Z" ), actor, Actor.Kind.SYSTEM, null,
            WorkEvent.CHANGE_MERGED, null, null, null );
    }

    @Test
    void aSystemEventIsNamedForItsHostAndTheDirectoryIsNeverAsked() {
        final WorkDirectory directory = mock( WorkDirectory.class );
        final WorkMcpJson json = new WorkMcpJson( directory, Map.of(), Map.of() );
        final Map< String, Object > event = json.event( merged( "codehost:github" ) );
        assertEquals( "system", event.get( "actorKind" ) );
        assertEquals( "GitHub", event.get( "actor" ) );
        assertEquals( "Code host", json.event( merged( "codehost:gitea" ) ).get( "actor" ) );
        verifyNoInteractions( directory );
    }
}
