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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WikantikHtmlSanitizerTest {

    @Test
    void keepsCalloutMarkup() {
        final String html = "<details class=\"callout callout-tip\" data-callout=\"tip\" open=\"\">"
                + "<summary class=\"callout-title\">T</summary><div class=\"callout-content\"><p>x</p></div></details>";
        final String out = WikantikHtmlSanitizer.sanitize( html );
        assertTrue( out.contains( "data-callout=\"tip\"" ), out );
        assertTrue( out.contains( "open" ), out );
        assertTrue( out.contains( "class=\"callout callout-tip\"" ), out );
    }

    @Test
    void rejectsNonWordCalloutAttributeValues() {
        final String out = WikantikHtmlSanitizer.sanitize( "<div data-callout=\"x onload=alert(1)\">y</div>" );
        assertFalse( out.contains( "data-callout" ), out );
    }
}
