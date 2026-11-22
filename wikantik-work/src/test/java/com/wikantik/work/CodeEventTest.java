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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeEventTest {

    private static CodeEvent with( final List< CodeEvent.Commit > commits ) {
        return new CodeEvent( CodeEvent.Kind.PUSH, "o/r", "main", "main", null, "a", "b", Instant.EPOCH, commits, null,
            null, null );
    }

    @Test
    void nullCommitsBecomeAnEmptyList() {
        assertTrue( with( null ).commits().isEmpty() );
    }

    @Test
    void theCommitListIsACopyAndUnmodifiable() {
        final List< CodeEvent.Commit > source = new ArrayList<>();
        source.add( new CodeEvent.Commit( "abc1234", "m", Instant.EPOCH, true, null ) );
        final CodeEvent e = with( source );
        source.clear();
        assertEquals( 1, e.commits().size() );
        assertThrows( UnsupportedOperationException.class, () -> e.commits().clear() );
    }
}
