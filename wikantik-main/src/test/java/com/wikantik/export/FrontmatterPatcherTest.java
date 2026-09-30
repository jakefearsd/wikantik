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
package com.wikantik.export;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FrontmatterPatcherTest {
    @Test void replacesRelatedAndAppendsNewKeysPreservingOtherLines() {
        final String raw = "---\ntype: article\ndate: 2026-01-15\nrelated:\n  - Foo\n  - Bar\ntags: [a, b]\n---\n# Body\n";
        final Map< String, Object > patch = new LinkedHashMap<>();
        patch.put( "related", List.of( "[[Foo]]", "[[Bar]]" ) );
        patch.put( "wikantik_url", "https://w.example/wiki/Page" );
        final String out = FrontmatterPatcher.patch( raw, patch );
        assertEquals( "---\ntype: article\ndate: 2026-01-15\ntags: [a, b]\n"
                + "related:\n  - \"[[Foo]]\"\n  - \"[[Bar]]\"\n"
                + "wikantik_url: \"https://w.example/wiki/Page\"\n---\n# Body\n", out );
    }

    @Test void inlineListValueReplaced() {
        final String out = FrontmatterPatcher.patch( "---\nrelated: [Foo]\n---\nx", Map.of( "related", List.of( "[[Foo]]" ) ) );
        assertEquals( "---\nrelated:\n  - \"[[Foo]]\"\n---\nx", out );
    }

    @Test void noFrontmatterCreatesBlock() {
        assertEquals( "---\nwikantik_version: \"3\"\n---\nbody", FrontmatterPatcher.patch( "body", Map.of( "wikantik_version", "3" ) ) );
    }

    @Test void quotesEscaped() {
        assertTrue( FrontmatterPatcher.patch( "x", Map.of( "aliases", List.of( "Say \"hi\"" ) ) )
                .contains( "  - \"Say \\\"hi\\\"\"\n" ) );
    }

    @Test void malformedUnclosedBlockTreatsAsNoFrontmatter() {
        final String raw = "---\ntype: article\nno closing block\nbody text";
        final String out = FrontmatterPatcher.patch( raw, Map.of( "version", "1" ) );
        assertEquals( "---\nversion: \"1\"\n---\n" + raw, out );
    }
}
