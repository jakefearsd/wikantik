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

import com.wikantik.TestEngine;
import com.wikantik.api.core.Acl;
import com.wikantik.api.core.AclEntry;
import com.wikantik.api.core.Context;
import com.wikantik.api.core.Page;
import com.wikantik.api.frontmatter.FrontmatterParser;
import com.wikantik.api.spi.Wiki;
import com.wikantik.auth.WikiSecurityException;
import com.wikantik.auth.acl.AclDirectiveFixtures;
import com.wikantik.auth.acl.AclManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.StringReader;
import java.security.Permission;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RenderPathAccessRules} reads exactly the access rules the render path reads: for every fixture and for tens of
 * thousands of seeded token soups, the ACL built from its rule lines equals the ACL the real markup parser and its
 * access-rule post-processor set on a page from the same raw text.
 */
class RenderPathAccessRulesTest {

    private static final String[] FRAGMENTS = { "[{ALLOW view a}]", "[{ALLOW edit b,", "[{DENY view c", "[{ALLOWED edit d",
            "[{ALLOW VIEW e", "[{ ALLOW view f", "}]", "}]()", "](x)", ",\n g}]", "\n", "\n\n", "\n> ", "\n- ", " ", "x", "`",
            "```\n", "\n```\n", "<div>\n", "\n</div>\n", "$", "\n$$\n", "\\[", "[", "]", "(", ")", "{.cls}", "{#id}", "h,i",
            "---\n", "    ", "|", "\n| a | b |\n|---|---|\n| " };

    private static TestEngine engine;

    @BeforeAll
    static void start() {
        engine = TestEngine.build();
    }

    @AfterAll
    static void stop() {
        engine.stop();
    }

    private static Set< String > pairs( final Acl acl ) {
        final Set< String > out = new HashSet<>();
        if ( acl == null ) {
            return out;
        }
        for ( final Enumeration< AclEntry > entries = acl.aclEntries(); entries.hasMoreElements(); ) {
            final AclEntry entry = entries.nextElement();
            for ( final Enumeration< Permission > perms = entry.permissions(); perms.hasMoreElements(); ) {
                out.add( entry.getPrincipal().getName() + ":" + perms.nextElement().getActions() );
            }
        }
        return out;
    }

    /** What the render path sets: the markup parser, with its access-rule post-processor, over the raw text. */
    private static Set< String > renderPath( final String text ) throws Exception {
        final Page scratch = Wiki.contents().page( engine, "RenderPathScratch" );
        final Context ctx = Wiki.context().create( engine, scratch );
        new MarkdownParser( ctx, new StringReader( text ) ).parse();
        return pairs( scratch.getAcl() );
    }

    /** The ACL the rule lines {@link RenderPathAccessRules} found build, through the same ACL parser. */
    private static Set< String > mirrored( final String text ) {
        final Page scratch = Wiki.contents().page( engine, "RenderPathScratch" );
        final AclManager acls = engine.getManager( AclManager.class );
        final List< String > rejected = new ArrayList<>();
        for ( final RenderPathAccessRules.Rule rule : RenderPathAccessRules.find( text ) ) {
            try {
                acls.parseAcl( scratch, rule.ruleLine() );
            } catch ( final WikiSecurityException e ) {
                rejected.add( e.getMessage() );   // the render path shows an error for this rule and applies nothing
            }
        }
        assertTrue( rejected.stream().allMatch( m -> m != null && m.startsWith( "Invalid" ) ), rejected.toString() );
        return pairs( scratch.getAcl() );
    }

    static Stream< AclDirectiveFixtures.Fixture > fixtures() {
        return AclDirectiveFixtures.ALL.stream();
    }

    @ParameterizedTest( name = "{0}" )
    @MethodSource( "fixtures" )
    void readsWhatTheRenderPathReadsForEveryFixture( final AclDirectiveFixtures.Fixture f ) throws Exception {
        assertEquals( renderPath( f.text() ), mirrored( f.text() ) );
    }

    @Test
    void readsWhatTheRenderPathReadsOnSeededTokenSoups() throws Exception {
        final Random random = new Random( 0xACCE55L );
        int withRules = 0;
        for ( int i = 0; i < 4_000; i++ ) {
            final StringBuilder sb = new StringBuilder();
            for ( int p = random.nextInt( 18 ); p >= 0; p-- ) {
                sb.append( FRAGMENTS[ random.nextInt( FRAGMENTS.length ) ] );
            }
            final String text = sb.toString();
            final Set< String > expected = renderPath( text );
            assertEquals( expected, mirrored( text ), "input #" + i + ": " + text );
            if ( !expected.isEmpty() ) {
                withRules++;
            }
        }
        assertTrue( withRules > 400, "too few soups carry a rule the render path reads: " + withRules );
    }

    @ParameterizedTest( name = "{0}" )
    @MethodSource( "fixtures" )
    void eachRuleSpansItsLinkInTheBody( final AclDirectiveFixtures.Fixture f ) {
        final int bodyStart = FrontmatterParser.bodyOffset( f.text() );
        for ( final RenderPathAccessRules.Rule rule : RenderPathAccessRules.find( f.text() ) ) {
            final String source = f.text().substring( rule.start(), rule.end() );
            assertTrue( rule.start() >= bodyStart, "never in the frontmatter: " + rule );
            assertTrue( source.startsWith( "[{" ) && ( source.endsWith( "}]" ) || source.endsWith( "}]()" ) ), source );
        }
    }

    @Test
    void theRenderPathsShapesAreDocumentedByExample() {
        assertEquals( List.of( "ALLOW edit Admin,\n alice" ), lines( "[{ALLOW edit Admin,\n alice}]\nBody." ),
                "wrapped over lines" );
        assertEquals( List.of(), lines( "[{ALLOW edit Admin,\n\nalice}]\nBody." ), "never across a blank line" );
        assertEquals( List.of( "DENY view bob" ), lines( "[{DENY view bob}]" ), "DENY is read (as a grant)" );
        assertEquals( List.of( "ALLOWED edit bob" ), lines( "[{ALLOWED edit bob}]" ), "the keyword is only a prefix" );
        assertEquals( List.of(), lines( "[{ ALLOW view bob}]" ), "no space after the brace" );
        assertEquals( List.of(), lines( "`[{ALLOW view bob}]`" ), "never in code" );
        assertEquals( List.of(), lines( "<details>\n```\n[{ALLOW view bob}]\n```\n</details>\n" ), "never in an HTML block" );
        assertEquals( List.of(), lines( "Formula $[{ALLOW view bob}]$ here." ), "never in inline math" );
        assertEquals( List.of(), lines( "---\nsummary: [{ALLOW view bob}]\n---\nBody." ), "never in the frontmatter" );
        assertEquals( List.of(), lines( "\\[{ALLOW view bob}]" ), "not when the bracket is escaped" );
        assertEquals( List.of(), lines( "[{ALLOW view bob}](http://example.test)" ), "not as the text of a real link" );
    }

    @Test
    void aLinkLabelHasNoLengthLimit() {
        final String principals = "someone.with.a.long.name@example.test,\n ".repeat( 3_000 );
        assertEquals( 1, lines( "[{ALLOW view " + principals + "last}]\nBody." ).size() );
    }

    @Test
    void aPageBuiltToBlowUpTheAttributesExpressionIsReadClosedAndQuickly() {
        // The renderer's attributes expression is quadratic on "{" + word + a long whitespace run.
        final String text = "[{ALLOW edit" + " ".repeat( 200_000 ) + "x\n\nsee [{ALLOW view bob}] here";
        assertTimeoutPreemptively( Duration.ofSeconds( 5 ), () -> {
            final List< String > lines = lines( text );
            assertTrue( lines.contains( "ALLOW view bob" ), lines.toString() );
        } );
    }

    @Test
    void theFailClosedReadingPairsEachCloserWithTheNearestOpener() {
        final List< RenderPathAccessRules.Rule > rules = RenderPathAccessRules.ruleShapedSpans(
                "[{ALLOW edit a [{ALLOW view b}] `[{DENY edit c}]()` x\n\n[{ALLOW view d\n\ne}]", 0 );
        assertEquals( List.of( "ALLOW view b", "DENY edit c" ), rules.stream().map( RenderPathAccessRules.Rule::ruleLine ).toList() );
    }

    private static List< String > lines( final String text ) {
        return RenderPathAccessRules.find( text ).stream().map( RenderPathAccessRules.Rule::ruleLine ).toList();
    }
}
