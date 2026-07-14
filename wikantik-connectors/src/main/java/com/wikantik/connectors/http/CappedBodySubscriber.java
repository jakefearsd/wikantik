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

import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicLong;

/** Accumulating byte[] subscriber that cancels the exchange once the body exceeds the cap.
 *  Works for chunked responses too (no Content-Length needed) — the count is enforced as
 *  buffers arrive, so memory is bounded by the cap regardless of what the server declares.
 *  Exceeding the cap completes the body exceptionally → {@code client.send} throws
 *  {@link IOException} → the caller fails closed. Shared by HttpPageFetcher and the
 *  GitHub/Confluence API clients. */
public final class CappedBodySubscriber implements HttpResponse.BodySubscriber< byte[] > {
    private final HttpResponse.BodySubscriber< byte[] > delegate = HttpResponse.BodySubscribers.ofByteArray();
    private final int max;
    private final AtomicLong received = new AtomicLong();
    private volatile Flow.Subscription subscription;
    private volatile boolean oversized;

    public CappedBodySubscriber( final int max ) { this.max = max; }

    @Override public CompletionStage< byte[] > getBody() { return delegate.getBody(); }

    @Override public void onSubscribe( final Flow.Subscription s ) {
        this.subscription = s;
        delegate.onSubscribe( s );
    }

    @Override public void onNext( final List< ByteBuffer > item ) {
        if ( oversized ) return;
        long n = 0;
        for ( final ByteBuffer b : item ) n += b.remaining();
        if ( received.addAndGet( n ) > max ) {
            oversized = true;
            subscription.cancel();
            delegate.onError( new IOException( "response body exceeds max " + max + " bytes — dropped (fail-closed)" ) );
        } else {
            delegate.onNext( item );
        }
    }

    @Override public void onError( final Throwable t ) { if ( !oversized ) delegate.onError( t ); }

    @Override public void onComplete() { if ( !oversized ) delegate.onComplete(); }
}
