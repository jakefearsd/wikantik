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

import com.wikantik.collab.model.PageState;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The page-name index of {@link CollabSessionRegistry}: page name to {@link SessionSlot}. Its lock guards the map only
 * and is held for map operations alone, never across a session call or store I/O (lock order: slot, then this map,
 * then the session). During a rename a slot is briefly mapped under both names ({@link #reserve}).
 */
final class PageSlots {

    private final ReentrantLock lock = new ReentrantLock();
    /** Guarded by {@code lock}. */
    private final Map< String, SessionSlot > byPage = new HashMap<>();

    /** The page's slot, a fresh unopened one when it has none. */
    SessionSlot slotFor( final String pageName ) {
        lock.lock();
        try {
            return byPage.computeIfAbsent( pageName, k -> new SessionSlot() );
        } finally {
            lock.unlock();
        }
    }

    /** The page's slot, or null. */
    SessionSlot get( final String pageName ) {
        lock.lock();
        try {
            return byPage.get( pageName );
        } finally {
            lock.unlock();
        }
    }

    /** Removes every name mapped to {@code slot}. */
    void unmap( final SessionSlot slot ) {
        lock.lock();
        try {
            byPage.values().removeIf( x -> x == slot );
        } finally {
            lock.unlock();
        }
    }

    /**
     * Maps {@code to} to {@code slot} as well (a rename in progress): a joiner of {@code to} then waits on the slot.
     *
     * @throws IllegalStateException when another slot holds {@code to} (it has a live session)
     */
    void reserve( final String from, final String to, final SessionSlot slot ) {
        lock.lock();
        try {
            final SessionSlot there = byPage.get( to );
            if( there != null && there != slot ) {
                throw new IllegalStateException( "collab: cannot move the session of " + from + " to " + to
                    + ": it has a live session" );
            }
            byPage.put( to, slot );
        } finally {
            lock.unlock();
        }
    }

    /** Unmaps {@code name} when it still maps to {@code slot}. */
    void release( final String name, final SessionSlot slot ) {
        lock.lock();
        try {
            byPage.remove( name, slot );
        } finally {
            lock.unlock();
        }
    }

    /** True when two reads of a page (null: missing) show the same version and content. */
    static boolean samePage( final PageState a, final PageState b ) {
        return a == null ? b == null
            : b != null && a.version() == b.version() && a.contentHash().equals( b.contentHash() );
    }
}
