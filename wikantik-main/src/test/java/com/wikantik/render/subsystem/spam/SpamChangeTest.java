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
package com.wikantik.render.subsystem.spam;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * {@code SpamSubsystemBranchTest} constructs {@code new SpamChange()} in several places without
 * ever setting {@code change}, so {@code equals()}/{@code hashCode()} must tolerate a null
 * {@code change} field rather than NPE-ing.
 */
class SpamChangeTest {

    @Test
    void equalsOnUnsetInstanceDoesNotThrow() {
        final SpamChange a = new SpamChange();
        final SpamChange b = new SpamChange();

        assertDoesNotThrow( () -> a.equals( b ) );
    }

    @Test
    void hashCodeOnUnsetInstanceDoesNotThrow() {
        final SpamChange a = new SpamChange();

        assertDoesNotThrow( a::hashCode );
    }

    @Test
    void twoUnsetInstancesAreEqual() {
        final SpamChange a = new SpamChange();
        final SpamChange b = new SpamChange();

        assertEquals( a, b );
        assertEquals( a.hashCode(), b.hashCode() );
    }

    @Test
    void unsetInstanceIsNotEqualToSetInstance() {
        final SpamChange unset = new SpamChange();
        final SpamChange set = new SpamChange();
        set.change = "an edit";

        assertNotEquals( unset, set );
        assertNotEquals( set, unset );
    }

    @Test
    void equalsAgainstNonSpamChangeIsFalse() {
        final SpamChange unset = new SpamChange();

        assertFalse( unset.equals( "not a SpamChange" ) );
    }
}
