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

import java.util.List;
import java.util.function.Function;

/**
 * A server the harness tests run against: its factory, its crash recovery, its weight overlay and how many seeds per
 * thousand may end as UNSUPPORTED (the poisoned stub, Task 10 ruling; never the session).
 */
public record SimTarget( String name, Function< SimEnv, SimServer > factory, Function< SimEnv, SimServer > reload,
                         SimScheduler.Overlay overlay, int unsupportedPerMille ) {

    public static final SimTarget STUB = new SimTarget( "stub", SimScheduler.STUB, SimScheduler.STUB_RELOAD,
        SimScheduler.Overlay.NONE, 10 );
    public static final SimTarget SESSION = new SimTarget( "session", CollabSessionSimServer.SESSION,
        CollabSessionSimServer.SESSION_RELOAD, CollabSessionSimServer.OVERLAY, 0 );

    public static List< SimTarget > all() {
        return List.of( STUB, SESSION );
    }

    public SimScheduler scheduler( final long seed, final SimProfile profile, final int clients ) {
        return new SimScheduler( seed, profile, factory, reload, overlay, clients );
    }

    public SimTrace replay( final SimTrace trace ) {
        return SimScheduler.replay( trace, factory, reload );
    }

    @Override
    public String toString() {
        return name;
    }
}
