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
package com.wikantik.api.markdown;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class ObsidianCommentsTest {

    @Test
    void removesAnInlineComment() {
        assertEquals( "a  b", ObsidianComments.strip( "a %%hidden%% b" ) );
    }

    @Test
    void aMultiLineCommentLeavesTheSameNumberOfNewlines() {
        final String out = ObsidianComments.strip( "a %%x\ny\nz%% b\nnext" );
        assertEquals( "a \n\n b\nnext", out );
        assertEquals( "a %%x\ny\nz%% b\nnext".chars().filter( c -> c == '\n' ).count(), out.chars().filter( c -> c == '\n' ).count() );
    }

    @Test
    void anUnterminatedCommentStaysLiteral() {
        assertEquals( "a %%b\nmore text", ObsidianComments.strip( "a %%b\nmore text" ) );
    }

    @Test
    void codeFencesAndMermaidCommentsAreUntouched() {
        final String mermaid = "```mermaid\ngraph TD\n%% a mermaid comment\nA-->B\n```\n";
        assertSame( mermaid, ObsidianComments.strip( mermaid ) );
        assertEquals( "`%%x%%` ", ObsidianComments.strip( "`%%x%%` %%y%%" ) );
    }

    @Test
    void mathIsUntouched() {
        assertEquals( "$a %%b%% c$ ", ObsidianComments.strip( "$a %%b%% c$ %%d%%" ) );
        final String block = "$$\nx %% tex\ny %%\n$$\n";
        assertSame( block, ObsidianComments.strip( block ) );
    }

    @Test
    void textWithoutACommentIsReturnedAsIsAndNullIsNull() {
        final String text = "plain 100% text\n";
        assertSame( text, ObsidianComments.strip( text ) );
        assertNull( ObsidianComments.strip( null ) );
    }

    @Test
    void aHugeRunOfUnpairedMarkersIsLinear() {
        final String text = "%% x ".repeat( 1 ) + "%".repeat( 1 ) + ( "a $b " ).repeat( 20_000 ) + "\n%%\n";
        assertTimeoutPreemptively( java.time.Duration.ofSeconds( 5 ), () -> ObsidianComments.strip( text ) );
    }
}
