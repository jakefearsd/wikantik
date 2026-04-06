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
package com.wikantik.knowledge;

import com.wikantik.api.knowledge.KgNode;
import com.wikantik.api.knowledge.Provenance;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class NodeTextAssemblerTest {

    @Test
    void assembleWithFullMetadata() {
        final KgNode node = makeNode( "CustomerService", "domain-model", Map.of(
            "title", "Customer Service Module",
            "description", "Handles customer lifecycle and relationships",
            "summary", "CRM entity",
            "tags", List.of( "core", "business" ),
            "keywords", List.of( "crm", "entity" )
        ) );
        final String result = NodeTextAssembler.assemble( node, "Some page body content." );

        // Name should appear 3 times (boost)
        assertTrue( countOccurrences( result, "CustomerService" ) >= 3 );
        // Type should appear 2 times (boost)
        assertTrue( countOccurrences( result, "domain-model" ) >= 2 );
        // Properties should appear
        assertTrue( result.contains( "Customer Service Module" ) );
        assertTrue( result.contains( "Handles customer lifecycle" ) );
        assertTrue( result.contains( "CRM entity" ) );
        assertTrue( result.contains( "core" ) );
        assertTrue( result.contains( "crm" ) );
        // Body should appear
        assertTrue( result.contains( "Some page body content." ) );
    }

    @Test
    void assembleStubNodeNoBody() {
        final KgNode node = makeNode( "StubEntity", "concept", null );
        final String result = NodeTextAssembler.assemble( node, null );
        assertTrue( result.contains( "StubEntity" ) );
        assertTrue( result.contains( "concept" ) );
        assertFalse( result.isBlank() );
    }

    @Test
    void assembleHandlesNullProperties() {
        final KgNode node = makeNode( "MyNode", null, null );
        final String result = NodeTextAssembler.assemble( node, null );
        assertTrue( result.contains( "MyNode" ) );
    }

    @Test
    void stripMarkdownRemovesHeadings() {
        final String md = "# Heading One\n## Heading Two\nParagraph text.";
        final String result = NodeTextAssembler.stripMarkdown( md );
        assertFalse( result.contains( "#" ) );
        assertTrue( result.contains( "Heading One" ) );
        assertTrue( result.contains( "Paragraph text." ) );
    }

    @Test
    void stripMarkdownRemovesCodeFences() {
        final String md = "Before\n```java\npublic class Foo {}\n```\nAfter";
        final String result = NodeTextAssembler.stripMarkdown( md );
        assertFalse( result.contains( "public class" ) );
        assertTrue( result.contains( "Before" ) );
        assertTrue( result.contains( "After" ) );
    }

    @Test
    void stripMarkdownRemovesLinks() {
        final String md = "See [the docs](https://example.com) for info.";
        final String result = NodeTextAssembler.stripMarkdown( md );
        assertTrue( result.contains( "the docs" ) );
        assertFalse( result.contains( "https://example.com" ) );
    }

    @Test
    void stripMarkdownRemovesEmphasis() {
        final String md = "This is **bold** and *italic* and __underline__ text.";
        final String result = NodeTextAssembler.stripMarkdown( md );
        assertTrue( result.contains( "bold" ) );
        assertTrue( result.contains( "italic" ) );
        assertFalse( result.contains( "**" ) );
        assertFalse( result.contains( "__" ) );
    }

    @Test
    void stripMarkdownRemovesFrontmatter() {
        final String md = "---\ntitle: Test\ntags: [a, b]\n---\nActual content here.";
        final String result = NodeTextAssembler.stripMarkdown( md );
        assertFalse( result.contains( "title:" ) );
        assertTrue( result.contains( "Actual content here." ) );
    }

    @Test
    void stripMarkdownRemovesImages() {
        final String md = "Look at ![diagram](img.png) here.";
        final String result = NodeTextAssembler.stripMarkdown( md );
        assertTrue( result.contains( "diagram" ) );
        assertFalse( result.contains( "img.png" ) );
    }

    @Test
    void stripMarkdownRemovesHtmlTags() {
        final String md = "Text <b>bold</b> and <div class=\"x\">content</div> end.";
        final String result = NodeTextAssembler.stripMarkdown( md );
        assertTrue( result.contains( "bold" ) );
        assertFalse( result.contains( "<b>" ) );
        assertFalse( result.contains( "<div" ) );
    }

    @Test
    void stripMarkdownHandlesNull() {
        assertEquals( "", NodeTextAssembler.stripMarkdown( null ) );
        assertEquals( "", NodeTextAssembler.stripMarkdown( "" ) );
    }

    // ---- helpers ----

    private KgNode makeNode( final String name, final String type,
                             final Map< String, Object > props ) {
        return new KgNode( UUID.randomUUID(), name, type, name + ".md",
            Provenance.HUMAN_AUTHORED, props, Instant.now(), Instant.now() );
    }

    private int countOccurrences( final String text, final String sub ) {
        int count = 0;
        int idx = 0;
        while( ( idx = text.indexOf( sub, idx ) ) >= 0 ) {
            count++;
            idx += sub.length();
        }
        return count;
    }
}
