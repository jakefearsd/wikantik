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

import com.wikantik.TestEngine;
import com.wikantik.WikiPage;
import com.wikantik.api.core.Context;
import com.wikantik.api.spi.Wiki;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link LinkParsingOperations} covering both pure-logic classification
 * methods and engine-dependent link resolution.
 */
class LinkParsingOperationsTest {

    private TestEngine engine;
    private LinkParsingOperations lpo;

    @BeforeEach
    void setUp() {
        engine = TestEngine.build();
        final WikiPage page = new WikiPage( engine, "TestPage" );
        final Context context = Wiki.context().create( engine, page );
        lpo = new LinkParsingOperations( context );
    }

    @AfterEach
    void tearDown() {
        engine.stop();
    }

    // ---------------------------------------------------------------
    // isAccessRule
    // ---------------------------------------------------------------
    @Nested
    class IsAccessRule {

        @Test
        void allowRuleReturnsTrue() {
            assertTrue( lpo.isAccessRule( "{ALLOW view Admin}" ) );
        }

        @Test
        void denyRuleReturnsTrue() {
            assertTrue( lpo.isAccessRule( "{DENY edit Guest}" ) );
        }

        @Test
        void allowPrefixWithoutSpaceStillMatches() {
            // startsWith check does not require a space after the keyword
            assertTrue( lpo.isAccessRule( "{ALLOWX" ) );
        }

        @Test
        void denyPrefixWithoutSpaceStillMatches() {
            assertTrue( lpo.isAccessRule( "{DENYX" ) );
        }

        @Test
        void setMetadataIsNotAccessRule() {
            assertFalse( lpo.isAccessRule( "{SET author}" ) );
        }

        @Test
        void pluginIsNotAccessRule() {
            assertFalse( lpo.isAccessRule( "{INSERT SomePlugin}" ) );
        }

        @Test
        void variableIsNotAccessRule() {
            assertFalse( lpo.isAccessRule( "{$pagename}" ) );
        }

        @Test
        void plainTextIsNotAccessRule() {
            assertFalse( lpo.isAccessRule( "just some text" ) );
        }

        @Test
        void lowercaseAllowIsNotAccessRule() {
            // the check is case-sensitive
            assertFalse( lpo.isAccessRule( "{allow view Admin}" ) );
        }
    }

    // ---------------------------------------------------------------
    // isPluginLink
    // ---------------------------------------------------------------
    @Nested
    class IsPluginLink {

        @Test
        void insertPrefixIsPlugin() {
            assertTrue( lpo.isPluginLink( "{INSERT SomePlugin}" ) );
        }

        @Test
        void braceWithoutDollarIsPlugin() {
            // starts with { but not {$ => treated as plugin
            assertTrue( lpo.isPluginLink( "{SomePlugin}" ) );
        }

        @Test
        void variableLinkIsNotPlugin() {
            // {$ prefix is explicitly excluded
            assertFalse( lpo.isPluginLink( "{$variable}" ) );
        }

        @Test
        void setIsPlugin() {
            // {SET starts with { and not {$ => classified as plugin
            assertTrue( lpo.isPluginLink( "{SET" ) );
        }

        @Test
        void allowIsPlugin() {
            // {ALLOW starts with { and not {$ => classified as plugin
            assertTrue( lpo.isPluginLink( "{ALLOW view Admin}" ) );
        }

        @Test
        void plainTextIsNotPlugin() {
            assertFalse( lpo.isPluginLink( "not a plugin" ) );
        }

        @Test
        void dollarSignAloneIsNotPlugin() {
            assertFalse( lpo.isPluginLink( "{$" ) );
        }

        @Test
        void bareOpenBraceIsPlugin() {
            // single { does not start with {$ so it matches the second branch
            assertTrue( lpo.isPluginLink( "{" ) );
        }
    }

    // ---------------------------------------------------------------
    // isMetadata
    // ---------------------------------------------------------------
    @Nested
    class IsMetadata {

        @Test
        void setLinkIsMetadata() {
            assertTrue( lpo.isMetadata( "{SET author}" ) );
        }

        @Test
        void setWithoutSpaceIsMetadata() {
            assertTrue( lpo.isMetadata( "{SET" ) );
        }

        @Test
        void allowIsNotMetadata() {
            assertFalse( lpo.isMetadata( "{ALLOW view}" ) );
        }

        @Test
        void variableIsNotMetadata() {
            assertFalse( lpo.isMetadata( "{$pagename}" ) );
        }

        @Test
        void plainTextIsNotMetadata() {
            assertFalse( lpo.isMetadata( "some text" ) );
        }

        @Test
        void lowercaseSetIsNotMetadata() {
            assertFalse( lpo.isMetadata( "{set author}" ) );
        }
    }

    // ---------------------------------------------------------------
    // isVariableLink
    // ---------------------------------------------------------------
    @Nested
    class IsVariableLink {

        @Test
        void dollarVariableIsVariable() {
            assertTrue( lpo.isVariableLink( "{$pagename}" ) );
        }

        @Test
        void minimalDollarPrefixIsVariable() {
            assertTrue( lpo.isVariableLink( "{$" ) );
        }

        @Test
        void dollarWithLongNameIsVariable() {
            assertTrue( lpo.isVariableLink( "{$applicationname}" ) );
        }

        @Test
        void setIsNotVariable() {
            assertFalse( lpo.isVariableLink( "{SET" ) );
        }

        @Test
        void bareBraceIsNotVariable() {
            assertFalse( lpo.isVariableLink( "{" ) );
        }

        @Test
        void insertIsNotVariable() {
            assertFalse( lpo.isVariableLink( "{INSERT Plugin}" ) );
        }

        @Test
        void plainTextIsNotVariable() {
            assertFalse( lpo.isVariableLink( "hello" ) );
        }
    }

    // ---------------------------------------------------------------
    // isExternalLink
    // ---------------------------------------------------------------
    @Nested
    class IsExternalLink {

        @Test
        void httpIsExternal() {
            assertTrue( lpo.isExternalLink( "http://example.com" ) );
        }

        @Test
        void httpsIsExternal() {
            assertTrue( lpo.isExternalLink( "https://example.com" ) );
        }

        @Test
        void ftpIsExternal() {
            assertTrue( lpo.isExternalLink( "ftp://files.example.com" ) );
        }

        @Test
        void mailtoIsExternal() {
            assertTrue( lpo.isExternalLink( "mailto:user@example.com" ) );
        }

        @Test
        void smbIsExternal() {
            assertTrue( lpo.isExternalLink( "smb://share" ) );
        }

        @Test
        void newsIsExternal() {
            assertTrue( lpo.isExternalLink( "news:comp.lang.java" ) );
        }

        @Test
        void fileIsExternal() {
            assertTrue( lpo.isExternalLink( "file:///tmp/test.txt" ) );
        }

        @Test
        void telnetIsExternal() {
            assertTrue( lpo.isExternalLink( "telnet://host" ) );
        }

        @Test
        void wikiPageIsNotExternal() {
            assertFalse( lpo.isExternalLink( "WikiPage" ) );
        }

        @Test
        void singleCharHDoesNotFalseMatch() {
            // "h" must not falsely match the "http:" prefix.
            // The StartingComparator has a length > 1 guard, and
            // isExternalLink double-checks with startsWith.
            assertFalse( lpo.isExternalLink( "h" ) );
        }

        @Test
        void emptyStringIsNotExternal() {
            assertFalse( lpo.isExternalLink( "" ) );
        }

        @Test
        void plainPathIsNotExternal() {
            assertFalse( lpo.isExternalLink( "/some/path" ) );
        }

        @Test
        void unknownProtocolIsNotExternal() {
            assertFalse( lpo.isExternalLink( "xyz://something" ) );
        }

        @Test
        void bareProtocolNameIsExternal() {
            // "http:" alone should match — it starts with "http:"
            assertTrue( lpo.isExternalLink( "http:" ) );
        }
    }

    // ---------------------------------------------------------------
    // isInterWikiLink / interWikiLinkAt
    // ---------------------------------------------------------------
    @Nested
    class IsInterWikiLink {

        @Test
        void colonSeparatedIsInterWiki() {
            assertTrue( lpo.isInterWikiLink( "Wikipedia:Albert_Einstein" ) );
        }

        @Test
        void interWikiLinkAtReturnsCorrectIndex() {
            assertEquals( 9, lpo.interWikiLinkAt( "Wikipedia:Albert_Einstein" ) );
        }

        @Test
        void noColonIsNotInterWiki() {
            assertFalse( lpo.isInterWikiLink( "PageName" ) );
        }

        @Test
        void noColonReturnsMinusOne() {
            assertEquals( -1, lpo.interWikiLinkAt( "PageName" ) );
        }

        @Test
        void leadingColonIsInterWiki() {
            assertTrue( lpo.isInterWikiLink( ":leading" ) );
        }

        @Test
        void leadingColonAtIndexZero() {
            assertEquals( 0, lpo.interWikiLinkAt( ":leading" ) );
        }

        @Test
        void trailingColonIsInterWiki() {
            assertTrue( lpo.isInterWikiLink( "trailing:" ) );
        }

        @Test
        void multipleColonsReturnsFirstIndex() {
            assertEquals( 4, lpo.interWikiLinkAt( "wiki:page:sub" ) );
        }

        @Test
        void emptyStringIsNotInterWiki() {
            assertFalse( lpo.isInterWikiLink( "" ) );
        }
    }

    // ---------------------------------------------------------------
    // isImageLink
    // ---------------------------------------------------------------
    @Nested
    class IsImageLink {

        @Test
        void matchingPatternWithInliningEnabled() {
            final List< Pattern > patterns = List.of( Pattern.compile( ".*\\.jpg" ) );
            assertTrue( lpo.isImageLink( "photo.jpg", true, patterns ) );
        }

        @Test
        void matchingPatternWithInliningDisabled() {
            final List< Pattern > patterns = List.of( Pattern.compile( ".*\\.jpg" ) );
            assertFalse( lpo.isImageLink( "photo.jpg", false, patterns ) );
        }

        @Test
        void caseInsensitiveMatch() {
            // The code lowercases the link before matching, so a lowercase
            // pattern should match an uppercase link.
            final List< Pattern > patterns = List.of( Pattern.compile( ".*\\.jpg" ) );
            assertTrue( lpo.isImageLink( "PHOTO.JPG", true, patterns ) );
        }

        @Test
        void nonMatchingPatternReturnsFalse() {
            final List< Pattern > patterns = List.of( Pattern.compile( ".*\\.jpg" ) );
            assertFalse( lpo.isImageLink( "document.pdf", true, patterns ) );
        }

        @Test
        void emptyPatternListReturnsFalse() {
            assertFalse( lpo.isImageLink( "photo.jpg", true, Collections.emptyList() ) );
        }

        @Test
        void multiplePatterns_secondMatches() {
            final List< Pattern > patterns = List.of(
                Pattern.compile( ".*\\.gif" ),
                Pattern.compile( ".*\\.png" )
            );
            assertTrue( lpo.isImageLink( "image.png", true, patterns ) );
        }

        @Test
        void multiplePatterns_noneMatch() {
            final List< Pattern > patterns = List.of(
                Pattern.compile( ".*\\.gif" ),
                Pattern.compile( ".*\\.png" )
            );
            assertFalse( lpo.isImageLink( "image.bmp", true, patterns ) );
        }
    }

    // ---------------------------------------------------------------
    // linkExists (engine-dependent)
    // ---------------------------------------------------------------
    @Nested
    class LinkExists {

        @Test
        void nullPageReturnsFalse() {
            assertFalse( lpo.linkExists( null ) );
        }

        @Test
        void emptyPageReturnsFalse() {
            assertFalse( lpo.linkExists( "" ) );
        }

        @Test
        void nonExistentPageReturnsFalse() {
            assertFalse( lpo.linkExists( "NonExistentPageXYZ123" ) );
        }

        @Test
        void existingPageReturnsTrue() throws Exception {
            engine.saveText( "LinkExistsTestPage", "some content" );
            assertTrue( lpo.linkExists( "LinkExistsTestPage" ) );
        }
    }

    // ---------------------------------------------------------------
    // linkIfExists (engine-dependent)
    // ---------------------------------------------------------------
    @Nested
    class LinkIfExists {

        @Test
        void nullPageReturnsNull() {
            assertNull( lpo.linkIfExists( null ) );
        }

        @Test
        void emptyPageReturnsNull() {
            assertNull( lpo.linkIfExists( "" ) );
        }

        @Test
        void nonExistentPageReturnsNull() {
            assertNull( lpo.linkIfExists( "NonExistentPageXYZ123" ) );
        }

        @Test
        void existingPageReturnsPageName() throws Exception {
            engine.saveText( "LinkIfExistsTestPage", "some content" );
            final String result = lpo.linkIfExists( "LinkIfExistsTestPage" );
            assertNotNull( result );
            assertEquals( "LinkIfExistsTestPage", result );
        }
    }

}
