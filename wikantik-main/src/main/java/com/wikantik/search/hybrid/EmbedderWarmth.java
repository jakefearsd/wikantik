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
package com.wikantik.search.hybrid;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Whether the embedding service is answering, so a timed-out query embed may be cancelled.
 *
 * <p>Cancelling a timed-out call matters under overload: an abandoned request would otherwise hold a place in the
 * embedder's queue. But Ollama aborts a model load whose client disconnects, so cancelling while the embedder is
 * loading its model (after a restart or an idle unload) aborts every load slower than the query timeout, and dense
 * search never comes back on its own. The two cases differ in one observable way: under overload other calls keep
 * completing, tens of milliseconds apart; while loading, nothing completes. So the embedder counts as answering
 * while some call completed within {@link #WINDOW}.</p>
 */
final class EmbedderWarmth {

    /** How recently some call must have completed for the embedder to count as answering. */
    static final Duration WINDOW = Duration.ofSeconds( 10 );

    private final Clock clock;
    /** When some call last completed normally (epoch millis); {@link Long#MIN_VALUE} = never. */
    private volatile long lastAnswerMillis = Long.MIN_VALUE;

    EmbedderWarmth( final Clock clock ) {
        this.clock = Objects.requireNonNull( clock, "clock" );
    }

    /** Notes the call's completion, whenever it comes — even after the caller has given up on it. */
    void watch( final CompletableFuture< ? > call ) {
        call.whenComplete( ( result, error ) -> {
            if( error == null ) {
                lastAnswerMillis = clock.millis();
            }
        } );
    }

    /** Some call completed within {@link #WINDOW}: the embedder's model is loaded and it is serving. */
    boolean isAnswering() {
        final long last = lastAnswerMillis;
        return last != Long.MIN_VALUE && clock.millis() - last <= WINDOW.toMillis();
    }
}
