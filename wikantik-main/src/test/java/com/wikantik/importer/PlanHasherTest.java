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

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PlanHasherTest {

    private static final ImportOptions FOLDERS = ImportOptions.parse( "folders", null );

    @Test
    void collidedNamesAreOrderInsensitive() {
        assertEquals( PlanHasher.hash( "z", FOLDERS, List.of( "B", "A" ), List.of() ), PlanHasher.hash( "z", FOLDERS, List.of( "A", "B", "A" ), List.of() ) );
    }

    @Test
    void differsByZipModeClusterAndCollisions() {
        final String base = PlanHasher.hash( "z", FOLDERS, List.of(), List.of() );
        assertEquals( 64, base.length() );
        assertNotEquals( base, PlanHasher.hash( "y", FOLDERS, List.of(), List.of() ) );
        assertNotEquals( base, PlanHasher.hash( "z", ImportOptions.parse( "none", null ), List.of(), List.of() ) );
        assertNotEquals( PlanHasher.hash( "z", ImportOptions.parse( "fixed", "a" ), List.of(), List.of() ),
            PlanHasher.hash( "z", ImportOptions.parse( "fixed", "b" ), List.of(), List.of() ) );
        assertNotEquals( base, PlanHasher.hash( "z", FOLDERS, List.of( "Alpha" ), List.of() ) );
    }

    @Test
    void differsByPlannedClusterOutcome() {
        final PlannedCluster create = new PlannedCluster( "f", ClusterAction.CREATE, "F Hub", "F" );
        final PlannedCluster join = new PlannedCluster( "f", ClusterAction.JOIN, "Other", "F" );
        final String a = PlanHasher.hash( "z", FOLDERS, List.of(), List.of( create ) );
        assertNotEquals( a, PlanHasher.hash( "z", FOLDERS, List.of(), List.of( join ) ) );
        assertNotEquals( a, PlanHasher.hash( "z", FOLDERS, List.of(), List.of() ) );
        assertEquals( a, PlanHasher.hash( "z", FOLDERS, List.of(), List.of( create ) ) );
    }
}
