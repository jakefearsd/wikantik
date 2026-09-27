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
package com.wikantik.attachment;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedirectTargetsTest {

    @ParameterizedTest
    @ValueSource( strings = { "/", "/wiki/Main", "/attach/Page/file.txt", "/Wiki.jsp?page=Main" } )
    void sameOriginRelativePathsAreAccepted( final String target ) {
        assertTrue( RedirectTargets.isSameOriginRelativePath( target ) );
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource( strings = { "//evil.com", "/\\evil.com", "\\\\evil.com", "/wiki\\x", "https://evil.com",
                              "http:evil.com", "javascript:alert(1)", "wiki/Main", "/wiki/Main\r\nSet-Cookie: x=1",
                              "/wiki/\tMain" } )
    void offOriginOrMalformedTargetsAreRejected( final String target ) {
        assertFalse( RedirectTargets.isSameOriginRelativePath( target ) );
    }

    @Test
    void validateNextPageReturnsSameInstanceWhenSafeAndFallbackOtherwise() {
        final String safe = "/wiki/Main";
        assertSame( safe, RedirectTargets.validateNextPage( safe, "/error" ) );
        assertEquals( "/error", RedirectTargets.validateNextPage( "//evil.com", "/error" ) );
        assertEquals( "/error", RedirectTargets.validateNextPage( null, "/error" ) );
    }
}
