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

import com.wikantik.collab.CollabPageWriter;
import com.wikantik.collab.CollabStore;

import java.util.Objects;
import java.util.function.BiFunction;

/**
 * F8: crash and recovery without production crash hooks. Crash points come from the doubles
 * ({@link InMemoryCollabStore#failOn}, {@link ScriptedPageWriter#persistThenFail}); "recovery" discards the server (the
 * caller drops its only reference, so every participant, parked pull and in-memory structure is gone) and builds a
 * fresh one over the <em>same</em> store and page writer. The fresh server must equal the last committed state (E37),
 * and clients that kept going must converge after their next pull or resync.
 */
public final class Recovery {

    private Recovery() {
    }

    /**
     * The fresh server over {@code store} and {@code writer}; fault points armed on the store stay armed (a recovery is
     * not a repair). For the stub: {@code (s, w) -> StubCollabAuthority.reload(page, s, w, clock, maxPageBytes, ringSize)}.
     */
    public static SimServer recover( final InMemoryCollabStore store, final ScriptedPageWriter writer,
                                     final BiFunction< CollabStore, CollabPageWriter, SimServer > reload ) {
        return Objects.requireNonNull( reload.apply( Objects.requireNonNull( store ), Objects.requireNonNull( writer ) ),
            "reload returned no server" );
    }
}
