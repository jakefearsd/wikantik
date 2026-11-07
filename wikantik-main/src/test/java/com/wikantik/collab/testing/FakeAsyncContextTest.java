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
package com.wikantik.collab.testing;

import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FakeAsyncContextTest {

    /** Records every callback it receives. */
    private static class Recording implements AsyncListener {
        final List< String > calls = new ArrayList<>();
        AsyncEvent last;

        @Override
        public void onComplete( final AsyncEvent event ) {
            calls.add( "complete" );
            last = event;
        }

        @Override
        public void onTimeout( final AsyncEvent event ) {
            calls.add( "timeout" );
            last = event;
        }

        @Override
        public void onError( final AsyncEvent event ) {
            calls.add( "error" );
        }

        @Override
        public void onStartAsync( final AsyncEvent event ) {
            calls.add( "start" );
        }
    }

    @Test
    void aSingleCompleteIsRecordedAndNotifiesListeners() {
        final FakeAsyncContext ctx = new FakeAsyncContext();
        final Recording l = new Recording();
        ctx.addListener( l );
        assertFalse( ctx.isCompleted() );
        ctx.complete();
        assertTrue( ctx.isCompleted() );
        assertEquals( 1, ctx.completions() );
        assertEquals( List.of( "complete" ), l.calls );
        assertSame( ctx, l.last.getAsyncContext() );
        ctx.assertCompletedOnce();
    }

    @Test
    void aSecondCompleteFails() {
        final FakeAsyncContext ctx = new FakeAsyncContext();
        ctx.complete();
        final AssertionError e = assertThrows( AssertionError.class, ctx::complete );
        assertTrue( e.getMessage().contains( "twice" ), e.getMessage() );
        assertEquals( 2, ctx.completions() );
        assertThrows( AssertionError.class, ctx::assertCompletedOnce );
    }

    @Test
    void neverCompletedFailsTheCheck() {
        final AssertionError e = assertThrows( AssertionError.class, new FakeAsyncContext()::assertCompletedOnce );
        assertTrue( e.getMessage().contains( "never" ), e.getMessage() );
    }

    @Test
    void fireTimeoutInvokesEveryRegisteredListener() {
        final FakeAsyncContext ctx = new FakeAsyncContext();
        final Recording a = new Recording();
        final Recording b = new Recording();
        ctx.addListener( a );
        ctx.addListener( b, null, null );
        ctx.fireTimeout();
        assertEquals( List.of( "timeout" ), a.calls );
        assertEquals( List.of( "timeout" ), b.calls );
        assertSame( ctx, a.last.getAsyncContext() );
        // firing the timeout does not complete the request: the listener (the resource) must answer it
        assertFalse( ctx.isCompleted() );
    }

    @Test
    void aTimeoutListenerThatCompletesAnswersTheRequestOnce() {
        final FakeAsyncContext ctx = new FakeAsyncContext();
        ctx.addListener( new Recording() {
            @Override
            public void onTimeout( final AsyncEvent event ) {
                event.getAsyncContext().complete();
            }
        } );
        ctx.fireTimeout();
        ctx.assertCompletedOnce();
    }

    @Test
    void timeoutAfterCompleteFails() {
        final FakeAsyncContext ctx = new FakeAsyncContext();
        ctx.complete();
        assertThrows( AssertionError.class, ctx::fireTimeout );
    }

    @Test
    void timeoutValueRoundTrips() {
        final FakeAsyncContext ctx = new FakeAsyncContext();
        ctx.setTimeout( 25_000 );
        assertEquals( 25_000, ctx.getTimeout() );
    }

    @Test
    void startRunsTheTaskInline() {
        final FakeAsyncContext ctx = new FakeAsyncContext();
        final List< String > ran = new ArrayList<>();
        ctx.start( () -> ran.add( "ran" ) );
        assertEquals( List.of( "ran" ), ran );
    }
}
