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
package com.wikantik.api.frontmatter.schema;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FrontmatterWarningSinkTest {

    @AfterEach
    void cleanUp() {
        FrontmatterWarningSink.clear();
    }

    private static FieldViolation warn( final String msg ) {
        return FieldViolation.of( "summary", Severity.WARNING, "W", msg );
    }

    @Test
    void drainReturnsOnlyTheRequestedPagesWarningsAndRemovesThem() {
        FrontmatterWarningSink.put( "Outer", List.of( warn( "outer" ) ) );
        FrontmatterWarningSink.put( "Nested", List.of( warn( "nested" ) ) );
        assertEquals( "outer", FrontmatterWarningSink.drain( "Outer" ).get( 0 ).message() );
        assertTrue( FrontmatterWarningSink.drain( "Outer" ).isEmpty() );
        assertEquals( "nested", FrontmatterWarningSink.drain( "Nested" ).get( 0 ).message() );
    }

    @Test
    void nullPageNameUsesStableKey() {
        FrontmatterWarningSink.put( null, List.of( warn( "anon" ) ) );
        assertEquals( 1, FrontmatterWarningSink.drain( null ).size() );
        assertTrue( FrontmatterWarningSink.drain( "" ).isEmpty() );
    }

    @Test
    void clearDropsEverything() {
        FrontmatterWarningSink.put( "A", List.of( warn( "a" ) ) );
        FrontmatterWarningSink.clear();
        assertTrue( FrontmatterWarningSink.drain( "A" ).isEmpty() );
    }
}
