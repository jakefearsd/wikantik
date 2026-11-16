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

import com.wikantik.work.CommentView;
import com.wikantik.work.WorkConflictException;
import com.wikantik.work.WorkDeniedException;
import com.wikantik.work.WorkNotFoundException;
import com.wikantik.work.WorkValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CommentOnItemToolTest {

    private MockWork w;
    private CommentOnItemTool tool;

    @BeforeEach
    void setUp() throws SQLException {
        w = new MockWork();
        tool = new CommentOnItemTool( w.svc );
    }

    @Test
    void happyPathReturnsIdAndTime() throws SQLException {
        final Instant at = Instant.parse( "2030-01-01T00:00:00Z" );
        when( w.svc.addComment( w.agent, "PLAT-1", "Looks good" ) ).thenReturn(
            new CommentView( 42, 1, "builder", "Looks good", at, null, null, "<p>x</p>", List.of(), 7L ) );
        final var out = WorkMcpFixture.json( tool.call( w.agent, Map.of( "key", "PLAT-1", "body", "Looks good" ) ) );
        assertEquals( 42, out.get( "id" ).getAsLong() );
        assertEquals( at.toString(), out.get( "at" ).getAsString() );
    }

    @Test
    void nullActorRefusedAndServiceNeverCalled() {
        assertEquals( "This endpoint needs a work API key.",
            WorkMcpFixture.text( tool.call( null, Map.of( "key", "PLAT-1", "body", "x" ) ) ) );
        verifyNoInteractions( w.svc );
    }

    @Test
    void hiddenAndUnknownGiveIdenticalError() throws SQLException {
        when( w.svc.addComment( any(), eq( "SECRET-1" ), any() ) ).thenThrow( new WorkNotFoundException( "a" ) );
        when( w.svc.addComment( any(), eq( "PLAT-9" ), any() ) ).thenThrow( new WorkNotFoundException( "b" ) );
        final var a = tool.call( w.agent, Map.of( "key", "SECRET-1", "body", "x" ) );
        assertEquals( "Not found.", WorkMcpFixture.text( a ) );
        assertEquals( a, tool.call( w.agent, Map.of( "key", "PLAT-9", "body", "x" ) ) );
    }

    @Test
    void aBlankOrMissingBodyIsRejectedBeforeTheService() throws SQLException {
        assertEquals( "body: is required.", WorkMcpFixture.text( tool.call( w.agent, Map.of( "key", "PLAT-1", "body", "  " ) ) ) );
        assertEquals( "body: is required.", WorkMcpFixture.text( tool.call( w.agent, Map.of( "key", "PLAT-1" ) ) ) );
        assertEquals( "body: must be a string.", WorkMcpFixture.text( tool.call( w.agent, Map.of( "key", "PLAT-1", "body", 5 ) ) ) );
        verify( w.svc, never() ).addComment( any(), any(), any() );
    }

    @Test
    void unknownArgumentsAreRejectedByName() {
        assertEquals( "author: Unknown argument.",
            WorkMcpFixture.text( tool.call( w.agent, Map.of( "key", "PLAT-1", "body", "x", "author", "lena" ) ) ) );
        verifyNoInteractions( w.svc );
    }

    @Test
    void deniedValidationAndArchivedAreMapped() throws SQLException {
        when( w.svc.addComment( any(), any(), any() ) ).thenThrow( new WorkDeniedException( "No." ) )
            .thenThrow( new WorkValidationException( "body", "A comment may be at most 10 characters." ) )
            .thenThrow( new WorkConflictException( "Archived.", WorkConflictException.ARCHIVED ) );
        final Map< String, Object > a = Map.of( "key", "PLAT-1", "body", "x" );
        assertEquals( "denied: No.", WorkMcpFixture.text( tool.call( w.agent, a ) ) );
        assertEquals( "body: A comment may be at most 10 characters.", WorkMcpFixture.text( tool.call( w.agent, a ) ) );
        assertEquals( "archived: Archived.", WorkMcpFixture.text( tool.call( w.agent, a ) ) );
    }
}
