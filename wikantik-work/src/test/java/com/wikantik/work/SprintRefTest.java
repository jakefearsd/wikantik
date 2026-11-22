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
package com.wikantik.work;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SprintRefTest {

    @Test
    void parsesActiveNoneAndAnId() {
        assertEquals( SprintRef.Kind.ACTIVE, SprintRef.parse( "active" ).kind() );
        assertEquals( SprintRef.Kind.NONE, SprintRef.parse( "none" ).kind() );
        final SprintRef id = SprintRef.parse( "42" );
        assertEquals( SprintRef.Kind.ID, id.kind() );
        assertEquals( 42L, id.sprintId() );
    }

    @Test
    void blankIsNoFilter() {
        assertNull( SprintRef.parse( null ) );
        assertNull( SprintRef.parse( "  " ) );
    }

    @Test
    void refusesAnythingElseNamingSprint() {
        for ( final String bad : new String[] { "-1", "1.5", "latest", "1234567890123456789" } ) {
            final WorkValidationException e = assertThrows( WorkValidationException.class,
                () -> SprintRef.parse( bad ), bad );
            assertEquals( "sprint", e.field() );
        }
    }
}
