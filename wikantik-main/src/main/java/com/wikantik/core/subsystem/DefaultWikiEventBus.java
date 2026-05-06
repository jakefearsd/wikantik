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
import com.wikantik.event.WikiEventManager;

/**
 * Default {@link WikiEventBus} implementation: thin delegation to the
 * {@link WikiEventManager} static singleton. Existing event-firing code
 * paths and weak-reference listener semantics are preserved exactly.
 */
public final class DefaultWikiEventBus implements WikiEventBus {

    @Override
    public void fireEvent( final Object client, final WikiEvent event ) {
        WikiEventManager.fireEvent( client, event );
    }

    @Override
    public boolean addListener( final Object client, final WikiEventListener listener ) {
        return WikiEventManager.addWikiEventListener( client, listener );
    }

    @Override
    public boolean removeListener( final Object client, final WikiEventListener listener ) {
        return WikiEventManager.removeWikiEventListener( client, listener );
    }
}
