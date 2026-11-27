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
package com.wikantik.connectors.http;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/** The response-size cap: exactly {@code max} bytes pass, one more fails closed and cancels the stream. */
class CappedBodySubscriberTest {

    private static final class Sub implements Flow.Subscription {
        final AtomicBoolean cancelled = new AtomicBoolean();
        @Override public void request( final long n ) { }
        @Override public void cancel() { cancelled.set( true ); }
    }

    private static List< ByteBuffer > bytes( final int n ) {
        return List.of( ByteBuffer.wrap( new byte[ n ] ) );
    }

    @Test
    void bodyExactlyAtTheCapIsDeliveredAndNotCancelled() throws Exception {
        final CappedBodySubscriber s = new CappedBodySubscriber( 10 );
        final Sub sub = new Sub();
        s.onSubscribe( sub );
        s.onNext( bytes( 6 ) );
        s.onNext( bytes( 4 ) );
        s.onComplete();
        assertArrayEquals( new byte[ 10 ], s.getBody().toCompletableFuture().get() );
        assertFalse( sub.cancelled.get() );
    }

    @Test
    void oneByteOverTheCapFailsClosedAndCancelsTheStream() {
        final CappedBodySubscriber s = new CappedBodySubscriber( 10 );
        final Sub sub = new Sub();
        s.onSubscribe( sub );
        s.onNext( bytes( 6 ) );
        s.onNext( bytes( 5 ) );
        assertTrue( sub.cancelled.get(), "an oversized stream must be cancelled, not drained" );
        final ExecutionException e = assertThrows( ExecutionException.class,
                () -> s.getBody().toCompletableFuture().get() );
        assertInstanceOf( IOException.class, e.getCause() );
    }

    @Test
    void chunksArrivingAfterTheCapWasBlownAreIgnoredAndCompletionDoesNotRevive() {
        final CappedBodySubscriber s = new CappedBodySubscriber( 4 );
        s.onSubscribe( new Sub() );
        s.onNext( bytes( 5 ) );
        s.onNext( bytes( 1 ) );
        s.onComplete();
        s.onError( new IllegalStateException( "late" ) );
        final ExecutionException e = assertThrows( ExecutionException.class,
                () -> s.getBody().toCompletableFuture().get() );
        assertInstanceOf( IOException.class, e.getCause(), "the cap failure, not a later signal, is the outcome" );
    }

    @Test
    void anUpstreamErrorBeforeTheCapIsPropagated() {
        final CappedBodySubscriber s = new CappedBodySubscriber( 100 );
        s.onSubscribe( new Sub() );
        s.onNext( bytes( 3 ) );
        final IllegalStateException boom = new IllegalStateException( "reset" );
        s.onError( boom );
        final ExecutionException e = assertThrows( ExecutionException.class,
                () -> s.getBody().toCompletableFuture().get() );
        assertEquals( boom, e.getCause() );
    }

    @Test
    void multiBufferChunksAreSummedAgainstTheCap() {
        final CappedBodySubscriber s = new CappedBodySubscriber( 5 );
        final Sub sub = new Sub();
        s.onSubscribe( sub );
        s.onNext( List.of( ByteBuffer.wrap( new byte[ 3 ] ), ByteBuffer.wrap( new byte[ 3 ] ) ) );
        assertTrue( sub.cancelled.get() );
    }
}
