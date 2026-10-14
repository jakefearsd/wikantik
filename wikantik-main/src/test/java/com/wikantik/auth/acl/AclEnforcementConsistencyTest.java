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
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.spi.Wiki;
import com.wikantik.auth.acl.AclDirectiveFixtures.Fixture;
import com.wikantik.parser.markdown.MarkdownParser;
import com.wikantik.render.RenderingManager;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One ACL per page, whoever asks first. The ACL manager's text scan is the only enforcement: it covers what it always
 * did plus every shape the render path turns into an access rule (so enforcement only ever tightens), and no render,
 * metadata read or render order changes what {@code getPermissions} answers.
 */
class AclEnforcementConsistencyTest {

    /** Fixtures where the text scan already enforced more than the render path ever did. */
    private static final Set< Fixture > TEXT_SCAN_ENFORCES_MORE =
            Set.of( AclDirectiveFixtures.HTML_BLOCK, AclDirectiveFixtures.INLINE_MATH );

    private static TestEngine engine;

    @BeforeAll
    static void start() throws Exception {
        engine = TestEngine.build();
        for ( final Fixture f : AclDirectiveFixtures.ALL ) {
            engine.saveText( pageName( f ), f.text() );
        }
    }

    @AfterAll
    static void stop() {
        engine.deleteQuietly( AclDirectiveFixtures.ALL.stream().map( AclEnforcementConsistencyTest::pageName ).toArray( String[]::new ) );
        engine.stop();
    }

    static Stream< Fixture > fixtures() {
        return AclDirectiveFixtures.ALL.stream();
    }

    private static String pageName( final Fixture f ) {
        return "AclConsistency" + AclDirectiveFixtures.ALL.indexOf( f );
    }

    private static AclManager acls() {
        return engine.getManager( AclManager.class );
    }

    /** Distinct modification stamps keep each fresh instance out of the ACL manager's parsed-ACL cache. */
    private static final AtomicLong STAMP = new AtomicLong( 1_000_000L );

    /** A Page instance nobody has rendered or asked about yet, and that no cached ACL answers for. */
    private static Page freshPage( final Fixture f ) {
        final Page page = Wiki.contents().page( engine, pageName( f ) );
        page.setLastModified( new Date( STAMP.incrementAndGet() ) );
        return page;
    }

    /** {@code principal:action} for every grant in {@code acl}. */
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

    /** The ACL the render path sets on a page from the RAW text: the markup parser and its access-rule post-processor. */
    private static Set< String > renderPathAcl( final Fixture f ) throws Exception {
        final Page scratch = Wiki.contents().page( engine, "RenderPathProbe" );
        final Context ctx = Wiki.context().create( engine, scratch );
        new MarkdownParser( ctx, new StringReader( f.text() ) ).parse();
        return pairs( scratch.getAcl() );
    }

    private static void render( final Page page, final Fixture f ) {
        final Context ctx = Wiki.context().create( engine, page );
        engine.getManager( RenderingManager.class ).textToHTML( ctx, f.text() );
    }

    @ParameterizedTest( name = "{0}" )
    @MethodSource( "fixtures" )
    void theTextScanEnforcesTheFixturesAcl( final Fixture f ) {
        assertEquals( f.acl(), pairs( acls().getPermissions( freshPage( f ) ) ) );
    }

    @ParameterizedTest( name = "{0}" )
    @MethodSource( "fixtures" )
    void theTextScanCoversEverythingTheRenderPathWouldEnforce( final Fixture f ) throws Exception {
        final Set< String > render = renderPathAcl( f );
        assertTrue( f.acl().containsAll( render ), "render path " + render + " vs fixture " + f.acl() );
        if ( TEXT_SCAN_ENFORCES_MORE.contains( f ) ) {
            assertNotEquals( f.acl(), render, "premise: the render path enforces less here" );
        } else {
            assertEquals( f.acl(), render, "where the text scan enforced no more, the union is the render path's ACL" );
        }
    }

    @ParameterizedTest( name = "{0}" )
    @MethodSource( "fixtures" )
    void aPageReadThroughThePageManagerCarriesTheSameAcl( final Fixture f ) {
        final Page page = engine.getManager( PageManager.class ).getPage( pageName( f ) );
        assertEquals( f.acl(), pairs( acls().getPermissions( page ) ) );
    }

    @ParameterizedTest( name = "{0}" )
    @MethodSource( "fixtures" )
    void renderingFirstDoesNotDecideTheAcl( final Fixture f ) {
        final Page page = freshPage( f );
        render( page, f );
        assertEquals( f.acl(), pairs( acls().getPermissions( page ) ) );
    }

    @ParameterizedTest( name = "{0}" )
    @MethodSource( "fixtures" )
    void renderingAfterwardsDoesNotChangeTheAcl( final Fixture f ) {
        final Page page = freshPage( f );
        final Set< String > before = pairs( acls().getPermissions( page ) );
        render( page, f );
        assertEquals( before, pairs( acls().getPermissions( page ) ) );
        assertEquals( f.acl(), before );
    }

    @ParameterizedTest( name = "{0}" )
    @MethodSource( "fixtures" )
    void theEnforcedDirectivesAreTheOnesTheAclIsBuiltFrom( final Fixture f ) {
        final List< String > enforced = DefaultAclManager.aclDirectives( f.text() );
        assertEquals( f.acl().isEmpty(), enforced.isEmpty(), "enforced: " + enforced );
    }
}
