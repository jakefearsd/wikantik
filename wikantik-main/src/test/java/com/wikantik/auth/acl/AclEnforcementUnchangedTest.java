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
package com.wikantik.auth.acl;

import com.wikantik.TestEngine;
import com.wikantik.api.core.Acl;
import com.wikantik.api.core.AclEntry;
import com.wikantik.api.core.Context;
import com.wikantik.api.core.Page;
import com.wikantik.api.spi.Wiki;
import com.wikantik.auth.acl.AclDirectiveFixtures.Fixture;
import com.wikantik.parser.markdown.MarkdownParser;
import com.wikantik.render.RenderingManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.StringReader;
import java.security.Permission;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Enforcement is exactly what it was in 2.4.57 (display-names work changes what readers SEE, never who may do what):
 * <ul>
 *   <li>the ACL manager's text scan — single-line {@code [{ALLOW …}]} outside code — gives the same ACL for every
 *       fixture and for the round-1 review's repros (a page whose render parse throws still enforces its rule);</li>
 *   <li>a display render (both forms) still sets the page's ACL from the RAW text, as the render path always did,
 *       although the HTML it shows is built from the text without the rules — for every fixture and every review repro,
 *       including pages whose parse throws (whether on the raw text or only on the text without the rules);</li>
 * </ul>
 * (The metadata parse behind {@code PageManager.getPage} is the 2.4.57 code again, so a page read that way carries what
 * it carried before.) The disagreement between the two readings (wrapped, DENY, upper-case, ALLOWED, HTML-block and math rules) is
 * pre-existing and is left to its own issue; this test pins it so that nothing here changes it by accident.
 */
class AclEnforcementUnchangedTest {

    private static final Logger LOG = LogManager.getLogger( AclEnforcementUnchangedTest.class );

    private static TestEngine engine;

    @BeforeAll
    static void start() throws Exception {
        engine = TestEngine.build();
        for ( final Fixture f : all().toList() ) {
            engine.saveText( pageName( f ), f.text() );
        }
    }

    @AfterAll
    static void stop() {
        engine.deleteQuietly( all().map( AclEnforcementUnchangedTest::pageName ).toArray( String[]::new ) );
        engine.stop();
    }

    static Stream< Fixture > all() {
        return Stream.concat( AclDirectiveFixtures.ALL.stream(), AclDirectiveFixtures.ENFORCEMENT_REPROS.stream() );
    }

    private static String pageName( final Fixture f ) {
        return "AclUnchanged" + all().toList().indexOf( f );
    }

    private static AclManager acls() {
        return engine.getManager( AclManager.class );
    }

    /** Distinct modification stamps keep each fresh instance out of the ACL manager's parsed-ACL cache. */
    private static final AtomicLong STAMP = new AtomicLong( 2_000_000L );

    /** A Page instance nobody has rendered or asked about yet, and that no cached ACL answers for. */
    private static Page freshPage( final Fixture f ) {
        final Page page = Wiki.contents().page( engine, pageName( f ) );
        page.setLastModified( new Date( STAMP.incrementAndGet() ) );
        return page;
    }

    static Set< String > pairs( final Acl acl ) {
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

    /**
     * The ACL the render path sets from the RAW text: the markup parser and its access-rule post-processor. On a page
     * whose parse throws, the rules read before the throw are what a 2.4.57 render left on the page.
     */
    private static Set< String > rawRenderAcl( final Fixture f ) {
        final Page scratch = Wiki.contents().page( engine, "RawRenderProbe" );
        final Context ctx = Wiki.context().create( engine, scratch );
        rendering( f, "raw parse", () -> new MarkdownParser( ctx, new StringReader( f.text() ) ).parse() );
        return pairs( scratch.getAcl() );
    }

    private interface Render {
        void run() throws Exception;
    }

    /** Runs a render that may throw on the crash fixtures; what it left on the page is what the caller asserts. */
    private static void rendering( final Fixture f, final String what, final Render render ) {
        try {
            render.run();
        } catch ( final Exception e ) {
            LOG.info( "{} of fixture '{}' threw, as it does on this page's text: {}", what, f.name(), e.toString() );
        }
    }

    @ParameterizedTest( name = "{0}" )
    @MethodSource( "all" )
    void theTextScanEnforcesWhatItEnforcedBefore( final Fixture f ) {
        assertEquals( f.acl(), pairs( acls().getPermissions( freshPage( f ) ) ) );
    }

    @ParameterizedTest( name = "{0}" )
    @MethodSource( "all" )
    void theEnforcedDirectivesAreTheTextScansOnly( final Fixture f ) {
        final List< String > enforced = DefaultAclManager.aclDirectives( f.text() );
        assertEquals( f.acl().isEmpty(), enforced.isEmpty(), "enforced: " + enforced );
    }

    @ParameterizedTest( name = "{0}" )
    @MethodSource( "all" )
    void aDisplayRenderStillSetsTheAclTheRawRenderPathSets( final Fixture f ) {
        final Page page = freshPage( f );
        rendering( f, "display render", () ->
                engine.getManager( RenderingManager.class ).textToHTML( Wiki.context().create( engine, page ), f.text() ) );
        assertEquals( rawRenderAcl( f ), pairs( page.getAcl() ) );
    }

    @ParameterizedTest( name = "{0}" )
    @MethodSource( "all" )
    void anExplicitDisplayRenderStillSetsTheAclTheRawRenderPathSets( final Fixture f ) {
        final Page page = freshPage( f );
        rendering( f, "explicit display render", () -> engine.getManager( RenderingManager.class )
                .textToHTML( Wiki.context().create( engine, page ), f.text(), null, null, null, true, false ) );
        assertEquals( rawRenderAcl( f ), pairs( page.getAcl() ) );
    }
}
