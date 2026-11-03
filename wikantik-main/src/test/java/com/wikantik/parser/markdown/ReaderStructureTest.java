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
package com.wikantik.parser.markdown;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReaderStructureTest {

    private static void assertSame( final List< int[] > expected, final List< int[] > actual, final String what ) {
        assertEquals( expected.size(), actual.size(), what + " count" );
        for ( int i = 0; i < expected.size(); i++ ) {
            assertEquals( Arrays.toString( expected.get( i ) ), Arrays.toString( actual.get( i ) ), what + " #" + i );
        }
    }

    @Test
    void ofClassifiesCodeMathHtmlAndRulesAsRenderPathAccessRulesDid() {
        final String[] bodies = {
                "a `x` $y$ <b>h</b> [{ALLOW view Admin}]\n\n$$\nz\n$$\n",
                "intro\n\n```java\nint a = 1;\n```\n\nafter [{ALLOW view Bob}]\n",
                "intro\n\n    indented code\n    more\n\nafter [t](http://x.org)\n" };
        for ( final String body : bodies ) {
            final RenderPathAccessRules.Structure expected = RenderPathAccessRules.structure( body );
            final RenderPathAccessRules.Structure actual = ReaderStructure.of( body, 0 );
            assertEquals( expected.rules(), actual.rules() );
            assertEquals( expected.labels(), actual.labels() );
            assertSame( expected.code(), actual.code(), "code" );
            assertSame( expected.html(), actual.html(), "html" );
            assertSame( expected.math(), actual.math(), "math" );
            assertSame( expected.links(), actual.links(), "links" );
        }
    }

    @Test
    void budgetExhaustionIsUnreadable() {
        assertThrows( RenderPathAccessRules.Unreadable.class, () -> ReaderStructure.of( "a `b` c", 0, 1 ) );
    }
}
