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
package com.wikantik.rest.admin;

import com.wikantik.observability.JfrProfilingService;
import com.wikantik.observability.JfrProfilingService.RecordingInfo;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProfilingResourceTest {

    private static RecordingInfo runningInfo() {
        return new RecordingInfo( "abc", Instant.parse( "2026-05-20T10:00:00Z" ),
            60, "stress", Path.of( "/var/wikantik/profiling/x.jfr" ), 0L, "RUNNING" );
    }

    private static RecordingInfo finishedInfo() {
        return new RecordingInfo( "abc", Instant.parse( "2026-05-20T10:00:00Z" ),
            60, "stress", Path.of( "/var/wikantik/profiling/x.jfr" ), 12345L, "FINISHED" );
    }

    @Test
    void startReturnsJsonRecordingInfo() {
        final JfrProfilingService svc = mock( JfrProfilingService.class );
        when( svc.start( 60, "stress" ) ).thenReturn( runningInfo() );
        final ProfilingResource r = new ProfilingResource( svc );
        final String json = r.start( 60, "stress" );
        assertTrue( json.contains( "\"recording_id\":\"abc\"" ) );
        assertTrue( json.contains( "\"status\":\"RUNNING\"" ) );
        assertTrue( json.contains( "\"duration_s\":60" ) );
        assertTrue( json.contains( "\"label\":\"stress\"" ) );
    }

    @Test
    void startInvalidDurationMapsTo400() {
        final JfrProfilingService svc = mock( JfrProfilingService.class );
        when( svc.start( 0, "x" ) ).thenThrow(
            new IllegalArgumentException( "duration_s must be in [1..600]" ) );
        final ProfilingResource r = new ProfilingResource( svc );
        final ProfilingResource.RestError err = assertThrows( ProfilingResource.RestError.class,
            () -> r.start( 0, "x" ) );
        assertEquals( 400, err.status() );
        assertTrue( err.getMessage().contains( "duration_s" ) );
    }

    @Test
    void startWhileRunningMapsTo409() {
        final JfrProfilingService svc = mock( JfrProfilingService.class );
        when( svc.start( 60, "x" ) ).thenThrow(
            new IllegalStateException( "A recording is already running (recording_id=running-id)" ) );
        final ProfilingResource r = new ProfilingResource( svc );
        final ProfilingResource.RestError err = assertThrows( ProfilingResource.RestError.class,
            () -> r.start( 60, "x" ) );
        assertEquals( 409, err.status() );
        assertTrue( err.getMessage().contains( "already running" ) );
    }

    @Test
    void stopUnknownIdMapsTo404() {
        final JfrProfilingService svc = mock( JfrProfilingService.class );
        when( svc.stop( "no-such" ) ).thenThrow(
            new IllegalArgumentException( "Unknown recording_id: no-such" ) );
        final ProfilingResource r = new ProfilingResource( svc );
        final ProfilingResource.RestError err = assertThrows( ProfilingResource.RestError.class,
            () -> r.stop( "no-such" ) );
        assertEquals( 404, err.status() );
        assertTrue( err.getMessage().contains( "no-such" ) );
    }

    @Test
    void stopReturnsFinishedRecording() {
        final JfrProfilingService svc = mock( JfrProfilingService.class );
        when( svc.stop( "abc" ) ).thenReturn( finishedInfo() );
        final ProfilingResource r = new ProfilingResource( svc );
        final String json = r.stop( "abc" );
        assertTrue( json.contains( "\"status\":\"FINISHED\"" ) );
        assertTrue( json.contains( "\"size_bytes\":12345" ) );
    }

    @Test
    void listEmitsJsonArray() {
        final JfrProfilingService svc = mock( JfrProfilingService.class );
        when( svc.list() ).thenReturn( java.util.List.of( finishedInfo(), runningInfo() ) );
        final ProfilingResource r = new ProfilingResource( svc );
        final String json = r.list();
        assertTrue( json.startsWith( "[" ) && json.endsWith( "]" ) );
        assertTrue( json.contains( "\"status\":\"FINISHED\"" ) );
        assertTrue( json.contains( "\"status\":\"RUNNING\"" ) );
    }

    @Test
    void filePathForUnknownIdMapsTo404() {
        final JfrProfilingService svc = mock( JfrProfilingService.class );
        when( svc.get( "missing" ) ).thenReturn( null );
        final ProfilingResource r = new ProfilingResource( svc );
        final ProfilingResource.RestError err = assertThrows( ProfilingResource.RestError.class,
            () -> r.filePathFor( "missing" ) );
        assertEquals( 404, err.status() );
    }

    @Test
    void filePathForKnownIdReturnsPath() {
        final JfrProfilingService svc = mock( JfrProfilingService.class );
        when( svc.get( "abc" ) ).thenReturn( finishedInfo() );
        final ProfilingResource r = new ProfilingResource( svc );
        assertEquals( Path.of( "/var/wikantik/profiling/x.jfr" ), r.filePathFor( "abc" ) );
    }
}
