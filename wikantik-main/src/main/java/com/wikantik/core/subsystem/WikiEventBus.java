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
package com.wikantik.core.subsystem;

import com.wikantik.event.WikiEvent;
import com.wikantik.event.WikiEventListener;

/**
 * Injectable handle to the wiki event bus — interface form of the
 * {@link com.wikantik.event.WikiEventManager} static singleton.
 *
 * <p>Phase 2 of the wikantik-main subsystem decomposition. See
 * {@code docs/superpowers/specs/2026-05-05-wikantik-main-decomposition-design.md}.</p>
 *
 * <p>Components that fire or subscribe to wiki events should depend on this
 * interface rather than calling {@code WikiEventManager.fireEvent(...)}
 * directly. Tests can pass a fake event bus instead of bringing up the
 * static manager — same testability win we got from extracting Knowledge
 * services into a typed bundle.</p>
 *
 * <p>Listener lifecycle: like the underlying static manager,
 * {@link com.wikantik.event.WikiEventManager} stores listeners as
 * {@link java.lang.ref.WeakReference}s. Components that register a
 * listener must keep a strong reference to it (typically by stashing the
 * listener as a field on the registering object) to keep it alive.</p>
 */
public interface WikiEventBus {

    /** Fires {@code event} from {@code client}; identical to {@link com.wikantik.event.WikiEventManager#fireEvent(Object, WikiEvent)}. */
    void fireEvent( Object client, WikiEvent event );

    /**
     * Registers {@code listener} as a subscriber for events fired from
     * {@code client}. Returns {@code true} when registration was new (the
     * listener wasn't already subscribed).
     */
    boolean addListener( Object client, WikiEventListener listener );

    /**
     * Removes {@code listener}'s subscription for events fired from
     * {@code client}. Returns {@code true} when an existing registration
     * was removed.
     */
    boolean removeListener( Object client, WikiEventListener listener );
}
