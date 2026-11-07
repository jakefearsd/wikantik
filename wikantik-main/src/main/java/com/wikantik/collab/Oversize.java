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
package com.wikantik.collab;

import java.nio.charset.StandardCharsets;

/**
 * Oversize state (section 7.4): entered when a publish rewrite, initial text or load lands the document over
 * {@code maxPageBytes} (the ceiling is set afresh each time), ratcheted down by shrinking pushes, cleared once the
 * document is within the limit. Pushes themselves are refused rather than allowed to cross the limit (413). Guarded by
 * the session lock.
 */
final class Oversize {

    private final long maxPageBytes;
    private boolean active;
    private long ceiling;

    Oversize( final long maxPageBytes ) {
        if( maxPageBytes < 1 ) {
            throw new IllegalArgumentException( "maxPageBytes must be positive: " + maxPageBytes );
        }
        this.maxPageBytes = maxPageBytes;
    }

    /** {@code mayEnter} is false for pushes, which only ratchet an existing ceiling down. */
    void update( final String doc, final boolean mayEnter ) {
        final long bytes = utf8( doc );
        if( bytes <= maxPageBytes ) {
            active = false;
            ceiling = 0;
        } else if( mayEnter ) {
            active = true;
            ceiling = bytes;
        } else if( active ) {
            ceiling = Math.min( ceiling, bytes );
        }
    }

    boolean active() {
        return active;
    }

    long ceiling() {
        return ceiling;
    }

    long maxPageBytes() {
        return maxPageBytes;
    }

    static long utf8( final String s ) {
        return s.getBytes( StandardCharsets.UTF_8 ).length;
    }
}
