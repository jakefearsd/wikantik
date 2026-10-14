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

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** {@link FrontmatterParser#bodyOffset(String)} is where {@link FrontmatterParser#parse(String)}'s body begins. */
class FrontmatterParserBodyOffsetTest {

    @ParameterizedTest
    @ValueSource( strings = {
            "no frontmatter at all",
            "---\ntitle: X\n---\nBody.\n",
            "---\r\ntitle: X\r\n---\r\nBody.\r\n",
            "---\n---\nBody after an empty block",
            "---\r\n---\r\nBody after an empty CRLF block",
            "---\ntitle: X\n---",
            "---\ntitle: X\nnever closed\n",
            "---\nnot: [valid yaml\n---\nBody.",
            "---\ntitle: X\n---\n",
            "---" } )
    void theOffsetIsWhereTheParsedBodyBegins( final String text ) {
        final String body = FrontmatterParser.parse( text ).body();
        assertEquals( text.length() - body.length(), FrontmatterParser.bodyOffset( text ) );
        assertEquals( body, text.substring( FrontmatterParser.bodyOffset( text ) ) );
    }

    @Test
    void nullAndEmptyHaveNoFrontmatter() {
        assertEquals( 0, FrontmatterParser.bodyOffset( null ) );
        assertEquals( 0, FrontmatterParser.bodyOffset( "" ) );
    }
}
