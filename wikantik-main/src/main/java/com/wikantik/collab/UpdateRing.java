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

import com.wikantik.collab.model.StoredUpdate;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * The last {@code capacity} accepted updates, oldest first (section 7.6): a contiguous suffix of the log ending at the
 * head. Not thread-safe: guarded by the session lock.
 */
final class UpdateRing {

    private final int capacity;
    private final Deque< StoredUpdate > updates = new ArrayDeque<>();

    UpdateRing( final int capacity ) {
        if( capacity < 1 ) {
            throw new IllegalArgumentException( "ring capacity must be >= 1: " + capacity );
        }
        this.capacity = capacity;
    }

    void add( final StoredUpdate u ) {
        if( !updates.isEmpty() && u.version() != updates.peekLast().version() + 1 ) {
            throw new IllegalStateException( "ring is contiguous: version " + u.version() + " after "
                + updates.peekLast().version() );
        }
        updates.addLast( u );
        while( updates.size() > capacity ) {
            updates.removeFirst();
        }
    }

    void addAll( final List< StoredUpdate > list ) {
        list.forEach( this::add );
    }

    /** True when every update after {@code since} is held here (the ring is non-empty and starts at or before since + 1). */
    boolean covers( final int since ) {
        return !updates.isEmpty() && updates.peekFirst().version() <= since + 1;
    }

    /** The held updates with a version above {@code since}, ascending. */
    List< StoredUpdate > after( final int since ) {
        final List< StoredUpdate > out = new ArrayList<>();
        for( final StoredUpdate u : updates ) {
            if( u.version() > since ) {
                out.add( u );
            }
        }
        return out;
    }

    List< StoredUpdate > asList() {
        return List.copyOf( updates );
    }
}
