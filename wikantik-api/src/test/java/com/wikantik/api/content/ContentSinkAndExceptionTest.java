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
package com.wikantik.api.content;

import com.wikantik.api.frontmatter.schema.Severity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ContentSinkAndExceptionTest {

    @AfterEach
    void cleanUp() {
        ContentWarningSink.clear();
    }

    private static ContentViolation v( final String msg ) {
        return new ContentViolation( "body", Severity.ERROR, "C", msg, null );
    }

    @Test
    void drainReturnsStashedWarningsOnceThenEmpty() {
        assertTrue( ContentWarningSink.drain().isEmpty() );
        ContentWarningSink.put( List.of( v( "x" ) ) );
        assertEquals( 1, ContentWarningSink.drain().size() );
        assertTrue( ContentWarningSink.drain().isEmpty() );
    }

    @Test
    void clearDiscardsWarnings() {
        ContentWarningSink.put( List.of( v( "x" ) ) );
        ContentWarningSink.clear();
        assertTrue( ContentWarningSink.drain().isEmpty() );
    }

    @Test
    void exceptionMessageSummarisesViolations() {
        final ContentValidationException one = new ContentValidationException( List.of( v( "bad" ) ) );
        assertEquals( "Content validation failed (1 error): bad", one.getMessage() );
        final ContentValidationException two = new ContentValidationException( List.of( v( "a" ), v( "b" ) ) );
        assertEquals( "Content validation failed (2 errors): a; b", two.getMessage() );
        assertEquals( 2, two.violations().size() );
    }
}
