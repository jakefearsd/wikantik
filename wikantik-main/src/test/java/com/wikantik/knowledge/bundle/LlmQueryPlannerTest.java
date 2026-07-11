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
package com.wikantik.knowledge.bundle;

import org.junit.jupiter.api.Test;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LlmQueryPlannerTest {

    private static BundleDecompositionConfig cfg() {
        return new BundleDecompositionConfig( true, "gemma4-assist:latest", "http://localhost:11434", 4000, 4, 60, "rrf" );
    }

    @SuppressWarnings("unchecked")
    private static HttpResponse< String > resp( int status, String body ) {
        HttpResponse< String > r = mock( HttpResponse.class );
        when( r.statusCode() ).thenReturn( status );
        when( r.body() ).thenReturn( body );
        return r;
    }

    @Test void decomposesMultiPartQuery() throws Exception {
        HttpClient http = mock( HttpClient.class );
        HttpResponse< String > response = resp( 200,
            "{\"message\":{\"content\":\"{\\\"subqueries\\\":[\\\"canary deployment traffic splitting\\\",\\\"blue-green deployment cutover\\\"]}\"}}" );
        when( http.< String >send( any(), any() ) ).thenReturn( response );
        List< String > out = new LlmQueryPlanner( http, cfg() ).plan( "canary vs blue-green" );
        assertEquals( List.of( "canary deployment traffic splitting", "blue-green deployment cutover" ), out );
    }

    @Test void singleIntentReturnsPassthrough() throws Exception {
        HttpClient http = mock( HttpClient.class );
        HttpResponse< String > response = resp( 200, "{\"message\":{\"content\":\"{\\\"subqueries\\\":[]}\"}}" );
        when( http.< String >send( any(), any() ) ).thenReturn( response );
        assertEquals( List.of( "one thing" ), new LlmQueryPlanner( http, cfg() ).plan( "one thing" ) );
    }

    @Test void non2xxFailsClosed() throws Exception {
        HttpClient http = mock( HttpClient.class );
        HttpResponse< String > response = resp( 500, "boom" );
        when( http.< String >send( any(), any() ) ).thenReturn( response );
        assertEquals( List.of( "q" ), new LlmQueryPlanner( http, cfg() ).plan( "q" ) );
    }

    @Test void malformedJsonFailsClosed() throws Exception {
        HttpClient http = mock( HttpClient.class );
        HttpResponse< String > response = resp( 200, "not json at all" );
        when( http.< String >send( any(), any() ) ).thenReturn( response );
        assertEquals( List.of( "q" ), new LlmQueryPlanner( http, cfg() ).plan( "q" ) );
    }

    @Test void ioExceptionFailsClosed() throws Exception {
        HttpClient http = mock( HttpClient.class );
        when( http.< String >send( any(), any() ) ).thenThrow( new java.io.IOException( "conn refused" ) );
        assertEquals( List.of( "q" ), new LlmQueryPlanner( http, cfg() ).plan( "q" ) );
    }

    @Test void interruptedFailsClosedAndReinterrupts() throws Exception {
        HttpClient http = mock( HttpClient.class );
        when( http.< String >send( any(), any() ) ).thenThrow( new InterruptedException( "interrupted" ) );
        List< String > out = new LlmQueryPlanner( http, cfg() ).plan( "q" );
        assertEquals( List.of( "q" ), out );                    // fail-closed to single-pass
        assertTrue( Thread.interrupted() );                     // interrupt flag re-set (and cleared here)
    }
}
