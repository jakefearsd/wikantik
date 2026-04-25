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
package com.wikantik.extractcli.mainpage;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PinsParserTest {

    @Test
    void empty_input_returns_empty_config() {
        final PinsConfig c = PinsParser.parse( "" );
        assertEquals( "", c.intro() );
        assertEquals( "", c.footer() );
        assertTrue( c.sections().isEmpty() );
    }

    @Test
    void parses_intro_footer_and_one_section() {
        final String yaml = """
                intro: |
                  Hello world.
                footer: Powered by Wikantik.
                sections:
                  - label: "Tech"
                    cluster: technology
                    pages:
                      - id: 01XXXXXXXXXXXXXXXXXXXXXXXX
                        summary: "Override"
                      - 01YYYYYYYYYYYYYYYYYYYYYYYY
                """;
        final PinsConfig c = PinsParser.parse( yaml );
        assertTrue( c.intro().contains( "Hello world" ) );
        assertEquals( "Powered by Wikantik.", c.footer() );
        assertEquals( 1, c.sections().size() );
        final PinsConfig.PinsSection s = c.sections().get( 0 );
        assertEquals( "Tech", s.label() );
        assertEquals( "technology", s.cluster() );
        assertEquals( 2, s.pages().size() );
        assertEquals( "01XXXXXXXXXXXXXXXXXXXXXXXX", s.pages().get( 0 ).canonicalId() );
        assertEquals( "Override", s.pages().get( 0 ).summaryOverride() );
        assertEquals( "01YYYYYYYYYYYYYYYYYYYYYYYY", s.pages().get( 1 ).canonicalId() );
        assertNull( s.pages().get( 1 ).summaryOverride() );
    }

    @Test
    void canonical_id_alias_works() {
        final String yaml = """
                sections:
                  - label: "X"
                    pages:
                      - canonical_id: 01ZZZZZZZZZZZZZZZZZZZZZZZZ
                """;
        final PinsConfig c = PinsParser.parse( yaml );
        assertEquals( "01ZZZZZZZZZZZZZZZZZZZZZZZZ",
                c.sections().get( 0 ).pages().get( 0 ).canonicalId() );
    }

    @Test
    void section_without_label_is_rejected() {
        final String yaml = """
                sections:
                  - pages: []
                """;
        assertThrows( IllegalArgumentException.class, () -> PinsParser.parse( yaml ) );
    }

    @Test
    void page_without_id_is_rejected() {
        final String yaml = """
                sections:
                  - label: "X"
                    pages:
                      - summary: "but no id"
                """;
        assertThrows( IllegalArgumentException.class, () -> PinsParser.parse( yaml ) );
    }

    @Test
    void scalar_root_is_rejected() {
        assertThrows( IllegalArgumentException.class, () -> PinsParser.parse( "just-a-string" ) );
    }
}
