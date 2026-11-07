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

import java.util.function.UnaryOperator;

/**
 * The doubles one simulated run is wired to, built fresh by the scheduler for every run and every replay: the clock it
 * advances (F5), the page writer (F4) and the store (F9). A server factory turns an environment into the server under
 * test, so the clock the scheduler moves is the clock the server reads.
 */
public record SimEnv( String pageName, MutableClock clock, InMemoryCollabStore store, ScriptedPageWriter writer,
                      long maxPageBytes, int ringSize ) {

    /**
     * The named, idempotent save-filter rewrites a trace may ask for. {@code strip-trailing-spaces} removes spaces before
     * a line end, which lands exactly where people type.
     */
    public static UnaryOperator< String > rewrite( final String name ) {
        return switch( name ) {
            case "none" -> UnaryOperator.identity();
            case "strip-trailing-spaces" -> s -> s.replaceAll( " +(?=\r?\n|$)", "" );
            default -> throw new IllegalArgumentException( "unknown rewrite: " + name );
        };
    }
}
