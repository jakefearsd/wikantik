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
package com.wikantik.extractcli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FrontmatterRewriterTest {

    @Test
    void adds_canonical_id_to_existing_frontmatter() {
        final String input = "---\ntitle: X\ntype: article\n---\nbody text";
        final String out = FrontmatterRewriter.assignCanonicalId( input, "01ABCDEFGHJKMNPQRSTVWXYZ12" );
        assertTrue( out.contains( "canonical_id: 01ABCDEFGHJKMNPQRSTVWXYZ12" ) );
        assertTrue( out.contains( "title: X" ) );
        assertTrue( out.contains( "body text" ) );
    }

    @Test
    void creates_frontmatter_block_when_absent() {
        final String input = "Just a body.\n";
        final String out = FrontmatterRewriter.assignCanonicalId( input, "01AAAAAAAAAAAAAAAAAAAAAAAA" );
        assertTrue( out.startsWith( "---\ncanonical_id: 01AAAAAAAAAAAAAAAAAAAAAAAA\n---\n" ) );
        assertTrue( out.contains( "Just a body." ) );
    }

    @Test
    void noop_when_canonical_id_already_present() {
        final String input = "---\ncanonical_id: 01XXXXXXXXXXXXXXXXXXXXXXXX\ntitle: X\n---\nbody";
        final String out = FrontmatterRewriter.assignCanonicalId( input, "01YYYYYYYYYYYYYYYYYYYYYYYY" );
        assertEquals( input, out );
    }

    @Test
    void preserves_crlf_endings() {
        final String input = "---\r\ntitle: X\r\n---\r\nbody\r\n";
        final String out = FrontmatterRewriter.assignCanonicalId( input, "01ZZZZZZZZZZZZZZZZZZZZZZZZ" );
        assertTrue( out.contains( "\r\n" ) );
        assertTrue( out.contains( "canonical_id: 01ZZZZZZZZZZZZZZZZZZZZZZZZ" ) );
    }
}
