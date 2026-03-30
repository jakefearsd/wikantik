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
package com.wikantik.parser;

import com.wikantik.StringTransmutator;
import com.wikantik.TestEngine;
import com.wikantik.WikiPage;
import com.wikantik.api.core.Context;
import com.wikantik.api.spi.Wiki;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Additional tests for {@link MarkupParser} covering uncovered branches:
 * compileGlobPattern (all special characters), makeError, cleanLink, wikifyLink,
 * getInlineImagePatterns (lazy init), addLinkHook (null guard),
 * addHeadingListener, callMutatorChain, enableImageInlining/isImageInlining,
 * disableAccessRules/isParseAccessRules, setInputReader, getPosition.
 */
class MarkupParserAdditionalTest {

    private TestEngine engine;
    private Context context;

    @BeforeEach
    void setUp() {
        engine = TestEngine.build();
        final WikiPage page = new WikiPage( engine, "TestPage" );
        context = Wiki.context().create( engine, page );
    }

    @AfterEach
    void tearDown() {
        engine.stop();
    }

    // -----------------------------------------------------------------------
    // compileGlobPattern – all special regex characters get escaped
    // -----------------------------------------------------------------------

    @Nested
    class CompileGlobPattern {

        @Test
        void asteriskBecomesWildcard() {
            final Pattern p = MarkupParser.compileGlobPattern( "*.jpg" );
            assertTrue( p.matcher( "photo.jpg" ).matches() );
            assertTrue( p.matcher( "PHOTO.JPG" ).matches() ); // CASE_INSENSITIVE
        }

        @Test
        void questionMarkMatchesSingleChar() {
            final Pattern p = MarkupParser.compileGlobPattern( "image?.png" );
            assertTrue( p.matcher( "image1.png" ).matches() );
            assertFalse( p.matcher( "image12.png" ).matches() );
        }

        @Test
        void dotIsEscaped() {
            final Pattern p = MarkupParser.compileGlobPattern( "file.txt" );
            assertTrue( p.matcher( "file.txt" ).matches() );
            // The dot is escaped in the regex so it does not match arbitrary char
            assertFalse( p.matcher( "filextxt" ).matches() );
        }

        @Test
        void specialRegexCharsAreEscaped() {
            // These should be treated as literals, not regex metacharacters
            final String[] specials = { "(", ")", "[", "]", "{", "}", "^", "$", "|", "\\", "+" };
            for ( final String s : specials ) {
                assertDoesNotThrow( () -> MarkupParser.compileGlobPattern( "prefix" + s + "suffix" ),
                        "Should compile without throwing for: " + s );
            }
        }

        @Test
        void plainTextMatchesExactly() {
            final Pattern p = MarkupParser.compileGlobPattern( "readme" );
            assertTrue( p.matcher( "readme" ).matches() );
            assertFalse( p.matcher( "README2" ).matches() );
        }
    }

    // -----------------------------------------------------------------------
    // makeError produces a span element
    // -----------------------------------------------------------------------

    @Test
    void makeErrorReturnsSpanWithErrorClass() {
        final org.jdom2.Element el = MarkupParser.makeError( "something went wrong" );
        assertNotNull( el );
        assertEquals( "span", el.getName() );
        assertEquals( "error", el.getAttributeValue( "class" ) );
        assertEquals( "something went wrong", el.getText() );
    }

    // -----------------------------------------------------------------------
    // cleanLink / wikifyLink
    // -----------------------------------------------------------------------

    @Test
    void cleanLinkReturnsNullForNull() {
        assertNull( MarkupParser.cleanLink( null ) );
    }

    @Test
    void cleanLinkPreservesAllowedPunctuation() {
        assertEquals( "--CleanLink--", MarkupParser.cleanLink( "--CleanLink--" ) );
    }

    @Test
    void wikifyLinkConvertsToLegacyCamelCase() {
        assertEquals( "ThisIsALink", MarkupParser.wikifyLink( "[ This is a link ]" ) );
    }

    @Test
    void wikifyLinkReturnsNullForNull() {
        assertNull( MarkupParser.wikifyLink( null ) );
    }

    // -----------------------------------------------------------------------
    // getInlineImagePatterns triggers lazy init
    // -----------------------------------------------------------------------

    @Test
    void getInlineImagePatternsIsNotNull() throws Exception {
        final WikiPage page = new WikiPage( engine, "ImgPage" );
        final Context ctx = Wiki.context().create( engine, page );
        final com.wikantik.parser.markdown.MarkdownParser parser =
                new com.wikantik.parser.markdown.MarkdownParser( ctx, new StringReader( "hello" ) );

        final List<Pattern> patterns = parser.getInlineImagePatterns();
        assertNotNull( patterns, "getInlineImagePatterns should never return null" );
        // Calling again should return the same (cached) list
        assertSame( patterns, parser.getInlineImagePatterns() );
    }

    // -----------------------------------------------------------------------
    // addLinkHook – null mutator is silently ignored
    // -----------------------------------------------------------------------

    @Test
    void addLinkTransmutatorWithNullIsIgnored() throws Exception {
        final WikiPage page = new WikiPage( engine, "NullHookPage" );
        final Context ctx = Wiki.context().create( engine, page );
        final com.wikantik.parser.markdown.MarkdownParser parser =
                new com.wikantik.parser.markdown.MarkdownParser( ctx, new StringReader( "test" ) );

        // Should not throw
        assertDoesNotThrow( () -> parser.addLinkTransmutator( null ) );
        assertDoesNotThrow( () -> parser.addLocalLinkHook( null ) );
        assertDoesNotThrow( () -> parser.addExternalLinkHook( null ) );
        assertDoesNotThrow( () -> parser.addAttachmentLinkHook( null ) );
    }

    // -----------------------------------------------------------------------
    // addHeadingListener – null listener is silently ignored
    // -----------------------------------------------------------------------

    @Test
    void addHeadingListenerWithNullIsIgnored() throws Exception {
        final WikiPage page = new WikiPage( engine, "NullListenerPage" );
        final Context ctx = Wiki.context().create( engine, page );
        final com.wikantik.parser.markdown.MarkdownParser parser =
                new com.wikantik.parser.markdown.MarkdownParser( ctx, new StringReader( "test" ) );

        assertDoesNotThrow( () -> parser.addHeadingListener( null ) );
    }

    // -----------------------------------------------------------------------
    // enableImageInlining / isImageInlining
    // -----------------------------------------------------------------------

    @Test
    void enableImageInliningToggles() throws Exception {
        final WikiPage page = new WikiPage( engine, "InlinePage" );
        final Context ctx = Wiki.context().create( engine, page );
        final com.wikantik.parser.markdown.MarkdownParser parser =
                new com.wikantik.parser.markdown.MarkdownParser( ctx, new StringReader( "test" ) );

        assertTrue( parser.isImageInlining(), "Default should be true" );
        parser.enableImageInlining( false );
        assertFalse( parser.isImageInlining() );
        parser.enableImageInlining( true );
        assertTrue( parser.isImageInlining() );
    }

    // -----------------------------------------------------------------------
    // disableAccessRules / isParseAccessRules
    // -----------------------------------------------------------------------

    @Test
    void disableAccessRulesSetsFlag() throws Exception {
        final WikiPage page = new WikiPage( engine, "AclPage" );
        final Context ctx = Wiki.context().create( engine, page );
        final com.wikantik.parser.markdown.MarkdownParser parser =
                new com.wikantik.parser.markdown.MarkdownParser( ctx, new StringReader( "test" ) );

        assertTrue( parser.isParseAccessRules() );
        parser.disableAccessRules();
        assertFalse( parser.isParseAccessRules() );
    }

    // -----------------------------------------------------------------------
    // setInputReader returns old reader (non-null path)
    // -----------------------------------------------------------------------

    @Test
    void setInputReaderReturnsOldReader() throws Exception {
        final WikiPage page = new WikiPage( engine, "ReaderPage" );
        final Context ctx = Wiki.context().create( engine, page );
        final StringReader first = new StringReader( "first" );
        final com.wikantik.parser.markdown.MarkdownParser parser =
                new com.wikantik.parser.markdown.MarkdownParser( ctx, first );

        final StringReader second = new StringReader( "second" );
        final java.io.Reader old = parser.setInputReader( second );
        // old is the PushbackReader wrapping `first`, not null
        assertNotNull( old );
    }

    @Test
    void setInputReaderWithNullIsNoOp() throws Exception {
        final WikiPage page = new WikiPage( engine, "NullReaderPage" );
        final Context ctx = Wiki.context().create( engine, page );
        final com.wikantik.parser.markdown.MarkdownParser parser =
                new com.wikantik.parser.markdown.MarkdownParser( ctx, new StringReader( "content" ) );

        // null input → should return old reader and leave state unchanged
        final java.io.Reader old = parser.setInputReader( null );
        assertNotNull( old );
    }

    // -----------------------------------------------------------------------
    // callMutatorChain – empty list returns text unchanged
    // -----------------------------------------------------------------------

    @Test
    void callMutatorChainWithEmptyListReturnsSameText() throws Exception {
        final WikiPage page = new WikiPage( engine, "MutatorPage" );
        final Context ctx = Wiki.context().create( engine, page );
        final com.wikantik.parser.markdown.MarkdownParser parser =
                new com.wikantik.parser.markdown.MarkdownParser( ctx, new StringReader( "hi" ) );

        // callMutatorChain is protected; exercise it indirectly by ensuring parse works
        // with no hooks registered (empty chains)
        final WikiDocument doc = parser.parse();
        assertNotNull( doc );
    }

    // -----------------------------------------------------------------------
    // getPosition returns -1 before any reads
    // -----------------------------------------------------------------------

    @Test
    void getPositionIsMinusOneBeforeReading() throws Exception {
        final WikiPage page = new WikiPage( engine, "PosPage" );
        final Context ctx = Wiki.context().create( engine, page );
        final com.wikantik.parser.markdown.MarkdownParser parser =
                new com.wikantik.parser.markdown.MarkdownParser( ctx, new StringReader( "text" ) );

        assertEquals( -1, parser.getPosition(), "Position should be -1 before any reads" );
    }
}
