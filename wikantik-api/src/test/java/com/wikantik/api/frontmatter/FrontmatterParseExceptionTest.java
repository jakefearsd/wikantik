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
package com.wikantik.api.frontmatter;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FrontmatterParseExceptionTest {

    @Test
    void carriesMessageAndPosition() {
        final FrontmatterParseException ex = new FrontmatterParseException( "bad yaml", 4, 12 );
        assertEquals( "bad yaml", ex.getMessage() );
        assertEquals( 4, ex.line() );
        assertEquals( 12, ex.column() );
        assertNull( ex.getCause() );
    }

    @Test
    void preservesCauseWhenSupplied() {
        final Throwable cause = new RuntimeException( "snakeyaml" );
        final FrontmatterParseException ex = new FrontmatterParseException( "bad yaml", 1, 1, cause );
        assertSame( cause, ex.getCause() );
        assertEquals( 1, ex.line() );
        assertEquals( 1, ex.column() );
    }

    @Test
    void unknownPositionUsesMinusOneSentinel() {
        final FrontmatterParseException ex = new FrontmatterParseException( "no position info", -1, -1 );
        assertEquals( -1, ex.line() );
        assertEquals( -1, ex.column() );
    }

    @Test
    void isACheckedException() {
        assertInstanceOf( Exception.class, new FrontmatterParseException( "m", -1, -1 ) );
        assertFalse( RuntimeException.class.isAssignableFrom( FrontmatterParseException.class ) );
    }
}
