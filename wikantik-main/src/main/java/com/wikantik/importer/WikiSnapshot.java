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
package com.wikantik.importer;

import java.util.Optional;

/** Read-only view of the target wiki taken once per import plan. */
public interface WikiSnapshot {

    /** The existing page matching {@code name}: exact, else case-insensitive (lexicographically lowest). */
    Optional< String > existingPage( String name );

    /** True when {@code name} is a system page that an import must never overwrite. */
    boolean isSystemPage( String name );

    /** The hub page declaring {@code cluster}, if any. */
    Optional< String > hubPage( String cluster );

    /** True when some page already declares {@code cluster}. */
    default boolean isClusterDeclared( final String cluster ) {
        return hubPage( cluster ).isPresent();
    }
}
