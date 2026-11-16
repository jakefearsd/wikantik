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
package com.wikantik.rest;

import com.wikantik.work.Actor;
import com.wikantik.work.WorkUnavailableException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.sql.SQLException;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Who the caller is, and what the client is told when that cannot be answered. */
class WorkCallersTest {

    private final HttpServletRequest request = Mockito.mock( HttpServletRequest.class );
    private final HttpServletResponse response = Mockito.mock( HttpServletResponse.class );
    private final StringWriter body = new StringWriter();

    private Actor authenticate( final java.util.function.Function< HttpServletRequest, Actor > resolve )
            throws Exception {
        Mockito.doReturn( new PrintWriter( body ) ).when( response ).getWriter();
        final Actor actor = WorkCallers.authenticate( resolve, request, response );
        response.getWriter().flush();
        return actor;
    }

    @Test
    void aResolvedActorIsReturnedAndNothingIsWritten() throws Exception {
        final Actor ana = Actor.human( "ana", Set.of(), false );
        assertSame( ana, authenticate( r -> ana ) );
        Mockito.verify( response, Mockito.never() ).setStatus( Mockito.anyInt() );
    }

    /** A sponsor lookup that did not answer: the agent is told to retry, not that its token is bad. */
    @Test
    void aDirectoryOutageWhileResolvingTheAgentIs503() throws Exception {
        assertNull( authenticate( r -> {
            throw new WorkUnavailableException( "The user directory could not be read." );
        } ) );
        Mockito.verify( response ).setStatus( 503 );
        assertEquals( WorkApiKeyFilter.UNAVAILABLE_BODY, body.toString() );
    }

    @Test
    void aStoreFailureWhileResolvingTheCallerIs503() throws Exception {
        assertNull( authenticate( r -> {
            throw new IllegalStateException( "Could not resolve the actor", new SQLException( "down" ) );
        } ) );
        Mockito.verify( response ).setStatus( 503 );
        assertEquals( WorkApiKeyFilter.UNAVAILABLE_BODY, body.toString() );
    }

    @Test
    void anyOtherFailureStaysA500() throws Exception {
        assertNull( authenticate( r -> {
            throw new IllegalArgumentException( "a bug" );
        } ) );
        Mockito.verify( response ).setStatus( 500 );
        assertTrue( !body.toString().contains( "a bug" ), "no internal detail reaches the client" );
    }
}
