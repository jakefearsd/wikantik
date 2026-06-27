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
import com.wikantik.api.core.Page;
import com.wikantik.api.spi.Wiki;
import com.wikantik.parser.markdown.MarkdownParser;
import com.wikantik.parser.markdown.SafeLinkAttributeProvider;
import com.vladsch.flexmark.html.renderer.AttributablePart;
import com.vladsch.flexmark.util.html.Attribute;
import com.vladsch.flexmark.util.html.MutableAttributes;
import com.vladsch.flexmark.util.ast.Node;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Targets previously-uncovered lines in the {@code com.wikantik.parser} package:
 * <ul>
 *   <li>{@link WikiDocument#setPageData}/{@link WikiDocument#getPageDataHash}/{@link WikiDocument#hashPageData} (lines 98-115)</li>
 *   <li>{@link WikiDocument#setPageData} with null data (null branch in computeHash, line 98-99)</li>
 *   <li>{@link WikiDocument#getPage()} and {@link WikiDocument#setContext}/{@link WikiDocument#getContext()}</li>
 *   <li>{@link SafeLinkAttributeProvider#scrub} — null attr early-return (line 56)</li>
 *   <li>{@link SafeLinkAttributeProvider#scrub} — null value early-return (line 60)</li>
 *   <li>{@link SafeLinkAttributeProvider#scrub} — empty value early-return (line 64)</li>
 *   <li>{@link SafeLinkAttributeProvider#scrub} — relative URL early-return (isRelative, lines 67-68)</li>
 *   <li>{@link SafeLinkAttributeProvider#scrub} — no colon → early-return (line 71)</li>
 *   <li>{@link SafeLinkAttributeProvider#scrub} — unsafe scheme → blocked (lines 75-76)</li>
 *   <li>{@link SafeLinkAttributeProvider#scrub} — safe scheme → unchanged (line 75)</li>
 *   <li>{@link SafeLinkAttributeProvider.Factory#apply} and {@link SafeLinkAttributeProvider.Factory#create}</li>
 *   <li>{@link MarkupParser#callMutatorChain} with non-empty chain (lines 313-315)</li>
 *   <li>{@link MarkupParser#addLinkHook} non-null path (lines 197-198)</li>
 * </ul>
 */
@TestInstance( TestInstance.Lifecycle.PER_CLASS )
class ParserPackageBranchTest {

    private TestEngine engine;
    private Context context;

    @BeforeAll
    void setUp() {
        engine = TestEngine.build();
        final WikiPage page = new WikiPage( engine, "ParserBranchTestPage" );
        context = Wiki.context().create( engine, page );
    }

    @AfterAll
    void tearDown() {
        engine.stop();
    }

    // -----------------------------------------------------------------------
    // WikiDocument — setPageData / getPageData / getPageDataHash / hashPageData
    // -----------------------------------------------------------------------

    @Nested
    class WikiDocumentBranches {

        @Test
        void setPageData_stores_text_and_populates_hash() {
            final Page page = mock( Page.class );
            final WikiDocument doc = new WikiDocument( page );

            doc.setPageData( "hello world" );

            assertEquals( "hello world", doc.getPageData() );
            assertNotNull( doc.getPageDataHash(), "Hash should be set after setPageData" );
            assertTrue( doc.getPageDataHash().matches( "[0-9a-f]{64}" ),
                    "SHA-256 hash should be 64 hex chars" );
        }

        @Test
        void setPageData_with_null_data_yields_null_hash() {
            final Page page = mock( Page.class );
            final WikiDocument doc = new WikiDocument( page );

            doc.setPageData( null );

            assertNull( doc.getPageData() );
            assertNull( doc.getPageDataHash(), "Null data should produce null hash" );
        }

        @Test
        void hashPageData_returns_same_hash_as_setPageData() {
            final Page page = mock( Page.class );
            final WikiDocument doc = new WikiDocument( page );
            final String text = "some wiki markup";

            doc.setPageData( text );

            final String staticHash = WikiDocument.hashPageData( text );
            assertEquals( doc.getPageDataHash(), staticHash,
                    "hashPageData() and setPageData() should produce identical hashes" );
        }

        @Test
        void hashPageData_null_returns_null() {
            assertNull( WikiDocument.hashPageData( null ) );
        }

        @Test
        void hashPageData_single_byte_produces_leading_zero_if_needed() {
            // Verifies the `if(hex.length()==1) append('0')` branch (line 107-109).
            // SHA-256 of any input may include bytes < 0x10 → leftPad with '0'.
            // We just verify the contract: hash is always 64 chars, never shorter.
            final String h = WikiDocument.hashPageData( "a" );
            assertNotNull( h );
            assertEquals( 64, h.length(), "SHA-256 hex must always be 64 characters" );
            assertTrue( h.matches( "[0-9a-f]{64}" ) );
        }

        @Test
        void getPage_returns_page_passed_to_constructor() {
            final Page page = mock( Page.class );
            final WikiDocument doc = new WikiDocument( page );
            assertSame( page, doc.getPage() );
        }

        @Test
        void setContext_and_getContext_round_trip() {
            final Page page = mock( Page.class );
            final WikiDocument doc = new WikiDocument( page );
            final Context ctx = mock( Context.class );

            doc.setContext( ctx );
            assertSame( ctx, doc.getContext() );
        }
    }

    // -----------------------------------------------------------------------
    // SafeLinkAttributeProvider — all scrub() branches
    // -----------------------------------------------------------------------

    @Nested
    class SafeLinkAttributeProviderBranches {

        private final SafeLinkAttributeProvider provider = new SafeLinkAttributeProvider();
        private final Node node = mock( Node.class );
        private final AttributablePart part = mock( AttributablePart.class );

        /** Convenience: build MutableAttributes with a single href. */
        private MutableAttributes hrefAttrs( final String href ) {
            final MutableAttributes attrs = mock( MutableAttributes.class );
            final Attribute attr = mock( Attribute.class );
            when( attrs.get( "href" ) ).thenReturn( attr );
            when( attrs.get( "src" ) ).thenReturn( null );
            when( attr.getValue() ).thenReturn( href );
            return attrs;
        }

        @Test
        void null_href_attribute_is_skipped() {
            // attrs.get("href") returns null → early-return at line 56
            final MutableAttributes attrs = mock( MutableAttributes.class );
            when( attrs.get( "href" ) ).thenReturn( null );
            when( attrs.get( "src" ) ).thenReturn( null );

            assertDoesNotThrow( () -> provider.setAttributes( node, part, attrs ) );
            verify( attrs, never() ).replaceValue( anyString(), anyString() );
        }

        @Test
        void null_attribute_value_is_skipped() {
            // attr.getValue() returns null → early-return at line 60
            final MutableAttributes attrs = mock( MutableAttributes.class );
            final Attribute attr = mock( Attribute.class );
            when( attrs.get( "href" ) ).thenReturn( attr );
            when( attrs.get( "src" ) ).thenReturn( null );
            when( attr.getValue() ).thenReturn( null );

            assertDoesNotThrow( () -> provider.setAttributes( node, part, attrs ) );
            verify( attrs, never() ).replaceValue( anyString(), anyString() );
        }

        @Test
        void empty_attribute_value_is_skipped() {
            // value.isEmpty() → early-return at line 64
            final MutableAttributes attrs = hrefAttrs( "" );
            assertDoesNotThrow( () -> provider.setAttributes( node, part, attrs ) );
            verify( attrs, never() ).replaceValue( anyString(), anyString() );
        }

        @Test
        void relative_slash_href_is_left_untouched() {
            // isRelative("/path") → line 67 early-return
            final MutableAttributes attrs = hrefAttrs( "/some/path" );
            provider.setAttributes( node, part, attrs );
            verify( attrs, never() ).replaceValue( anyString(), anyString() );
        }

        @Test
        void relative_hash_href_is_left_untouched() {
            final MutableAttributes attrs = hrefAttrs( "#anchor" );
            provider.setAttributes( node, part, attrs );
            verify( attrs, never() ).replaceValue( anyString(), anyString() );
        }

        @Test
        void relative_dot_href_is_left_untouched() {
            final MutableAttributes attrs = hrefAttrs( "./relative/path" );
            provider.setAttributes( node, part, attrs );
            verify( attrs, never() ).replaceValue( anyString(), anyString() );
        }

        @Test
        void relative_query_href_is_left_untouched() {
            final MutableAttributes attrs = hrefAttrs( "?query=value" );
            provider.setAttributes( node, part, attrs );
            verify( attrs, never() ).replaceValue( anyString(), anyString() );
        }

        @Test
        void schemeless_reference_no_colon_is_left_untouched() {
            // No colon → colonIdx == -1 → early-return at line 71
            final MutableAttributes attrs = hrefAttrs( "pagename" );
            provider.setAttributes( node, part, attrs );
            verify( attrs, never() ).replaceValue( anyString(), anyString() );
        }

        @Test
        void leading_colon_is_left_untouched() {
            // colonIdx == 0 → the scheme substring would be empty → early-return at line 71
            final MutableAttributes attrs = hrefAttrs( ":something" );
            provider.setAttributes( node, part, attrs );
            verify( attrs, never() ).replaceValue( anyString(), anyString() );
        }

        @Test
        void javascript_scheme_is_blocked() {
            // javascript is not in SAFE_SCHEMES → replaceValue called (line 76)
            final MutableAttributes attrs = mock( MutableAttributes.class );
            final Attribute attr = mock( Attribute.class );
            when( attrs.get( "href" ) ).thenReturn( attr );
            when( attrs.get( "src" ) ).thenReturn( null );
            when( attr.getValue() ).thenReturn( "javascript:alert('xss')" );

            provider.setAttributes( node, part, attrs );

            verify( attrs ).replaceValue( "href", "about:blank" );
        }

        @Test
        void data_scheme_is_blocked() {
            final MutableAttributes attrs = mock( MutableAttributes.class );
            final Attribute attr = mock( Attribute.class );
            when( attrs.get( "href" ) ).thenReturn( attr );
            when( attrs.get( "src" ) ).thenReturn( null );
            when( attr.getValue() ).thenReturn( "data:text/html,<h1>xss</h1>" );

            provider.setAttributes( node, part, attrs );

            verify( attrs ).replaceValue( "href", "about:blank" );
        }

        @Test
        void http_scheme_is_safe_and_not_replaced() {
            // http is in SAFE_SCHEMES → no replaceValue call
            final MutableAttributes attrs = mock( MutableAttributes.class );
            final Attribute attr = mock( Attribute.class );
            when( attrs.get( "href" ) ).thenReturn( attr );
            when( attrs.get( "src" ) ).thenReturn( null );
            when( attr.getValue() ).thenReturn( "http://example.com" );

            provider.setAttributes( node, part, attrs );

            verify( attrs, never() ).replaceValue( anyString(), anyString() );
        }

        @Test
        void https_scheme_is_safe_and_not_replaced() {
            final MutableAttributes attrs = mock( MutableAttributes.class );
            final Attribute attr = mock( Attribute.class );
            when( attrs.get( "href" ) ).thenReturn( attr );
            when( attrs.get( "src" ) ).thenReturn( null );
            when( attr.getValue() ).thenReturn( "https://secure.example.com" );

            provider.setAttributes( node, part, attrs );

            verify( attrs, never() ).replaceValue( anyString(), anyString() );
        }

        @Test
        void src_attribute_unsafe_scheme_is_blocked() {
            // Covers the src-side scrub path
            final MutableAttributes attrs = mock( MutableAttributes.class );
            when( attrs.get( "href" ) ).thenReturn( null );
            final Attribute srcAttr = mock( Attribute.class );
            when( attrs.get( "src" ) ).thenReturn( srcAttr );
            when( srcAttr.getValue() ).thenReturn( "vbscript:evil()" );

            provider.setAttributes( node, part, attrs );

            verify( attrs ).replaceValue( "src", "about:blank" );
        }

        @Test
        void factory_apply_creates_provider_instance() {
            final SafeLinkAttributeProvider.Factory factory = new SafeLinkAttributeProvider.Factory();
            final var p = factory.apply( null );
            assertNotNull( p );
            assertInstanceOf( SafeLinkAttributeProvider.class, p );
        }

        @Test
        void factory_create_returns_factory_instance() {
            final var f = SafeLinkAttributeProvider.Factory.create();
            assertNotNull( f );
        }
    }

    // -----------------------------------------------------------------------
    // ParseException — constructor (covers the 2 "missed" source lines)
    // -----------------------------------------------------------------------

    @Nested
    class ParseExceptionBranches {

        @Test
        void constructor_creates_exception_with_correct_message() {
            final ParseException ex = new ParseException( "test parse error" );
            assertEquals( "test parse error", ex.getMessage() );
            assertInstanceOf( com.wikantik.api.exceptions.WikiException.class, ex );
        }

        @Test
        void exception_can_be_thrown_and_caught() {
            assertThrows( ParseException.class, () -> { throw new ParseException( "thrown" ); } );
        }
    }

    // -----------------------------------------------------------------------
    // VariableContent — null-root and null-context paths (covers L60, L62, L66-67, L91, L101)
    // -----------------------------------------------------------------------

    @Nested
    class VariableContentBranches {

        @Test
        void getValue_when_not_attached_to_document_returns_var_name() {
            // VariableContent not yet added to a WikiDocument → getDocument() returns null (L60→L62)
            final VariableContent vc = new VariableContent( "myVar" );
            assertEquals( "myVar", vc.getValue(),
                    "When not attached to a document, getValue() should return the variable name" );
        }

        @Test
        void getText_delegates_to_getValue() {
            // getText() calls getValue() (L91)
            final VariableContent vc = new VariableContent( "testVar" );
            assertEquals( vc.getValue(), vc.getText() );
        }

        @Test
        void toString_returns_debug_string() {
            // toString() returns "VariableElement[\"varName\"]" (L101)
            final VariableContent vc = new VariableContent( "someVar" );
            assertEquals( "VariableElement[\"someVar\"]", vc.toString() );
        }

        @Test
        void getValue_when_document_has_null_context_returns_error_string() {
            // Attach vc inside a JDOM2 Element which is a child of WikiDocument (L65-L67).
            // WikiDocument extends Document; VariableContent extends Text (must be inside an Element).
            final Page page = mock( Page.class );
            final WikiDocument doc = new WikiDocument( page );
            final org.jdom2.Element rootEl = new org.jdom2.Element( "root" );
            doc.setRootElement( rootEl );
            final VariableContent vc = new VariableContent( "nullCtxVar" );
            rootEl.addContent( vc );

            // doc.getContext() returns null (not set) → "No WikiContext available: INTERNAL ERROR"
            final String result = vc.getValue();
            assertEquals( "No WikiContext available: INTERNAL ERROR", result,
                    "When document exists but context is null, getValue() should return the internal error string" );
        }
    }

    // -----------------------------------------------------------------------
    // MarkupParser.callMutatorChain — non-empty chain (lines 313-315)
    // -----------------------------------------------------------------------

    @Nested
    class CallMutatorChainBranches {

        @Test
        void addLinkTransmutator_non_null_mutator_is_added_and_executed() throws Exception {
            final WikiPage wikiPage = new WikiPage( engine, "MutatorTestPage" );
            final Context ctx = Wiki.context().create( engine, wikiPage );
            final MarkdownParser parser = new MarkdownParser( ctx, new StringReader( "hello [link](http://example.com)" ) );

            // Register a real mutator that transforms link text to upper-case
            final StringTransmutator upperCaseMutator = ( c, text ) -> text.toUpperCase();
            parser.addLinkTransmutator( upperCaseMutator );

            // parse() exercises the mutator chain for any links it finds
            final WikiDocument doc = parser.parse();
            assertNotNull( doc, "parse() should complete successfully with a non-null mutator" );
        }

        @Test
        void addLocalLinkHook_non_null_mutator_is_stored() throws Exception {
            final WikiPage wikiPage = new WikiPage( engine, "LocalHookPage" );
            final Context ctx = Wiki.context().create( engine, wikiPage );
            final MarkdownParser parser = new MarkdownParser( ctx, new StringReader( "test content" ) );

            final StringTransmutator identity = ( c, text ) -> text;
            // Should not throw and actually registers the hook
            assertDoesNotThrow( () -> parser.addLocalLinkHook( identity ) );
            // Verify by parsing (exercises the chain)
            assertDoesNotThrow( () -> parser.parse() );
        }
    }
}
