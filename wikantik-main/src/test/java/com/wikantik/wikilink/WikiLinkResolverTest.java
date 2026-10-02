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
package com.wikantik.wikilink;

import com.wikantik.api.exceptions.ProviderException;
import com.wikantik.api.pagegraph.PageTitleLookup;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WikiLinkResolverTest {

    private static PageTitleLookup lookup( final PageTitleLookup.TitleEntry... entries ) {
        final PageTitleLookup l = Mockito.mock( PageTitleLookup.class );
        Mockito.when( l.entries() ).thenReturn( List.of( entries ) );
        return l;
    }

    private static PageTitleLookup.TitleEntry entry( final String slug, final String... phrases ) {
        return new PageTitleLookup.TitleEntry( slug, slug, List.of( phrases ) );
    }

    private static WikiLinkResolver resolver( final Set< String > pages, final PageTitleLookup titles ) {
        return new WikiLinkResolver( n -> pages.contains( n ) ? n : null, () -> Optional.ofNullable( titles ) );
    }

    @Test
    void exactNameWins() {
        assertEquals( new WikiLinkResolver.Resolution( "Alpha", true ),
                resolver( Set.of( "Alpha" ), lookup( entry( "Alpha" ) ) ).resolve( "Alpha" ) );
    }

    @Test
    void exactLookupKeepsPluralMatching() {
        final WikiLinkResolver r = new WikiLinkResolver( n -> n.equals( "Dogs" ) ? "Dog" : null, Optional::empty );
        assertEquals( "Dog", r.resolve( "Dogs" ).pageName() );
    }

    @Test
    void caseInsensitivePageName() {
        assertEquals( "MachineLearning", resolver( Set.of( "MachineLearning" ),
                lookup( entry( "MachineLearning" ) ) ).resolve( "machinelearning" ).pageName() );
    }

    @Test
    void titleOrAliasPhrase() {
        final var r = resolver( Set.of( "Kubernetes" ), lookup( entry( "Kubernetes", "K8s Platform" ) ) );
        assertEquals( new WikiLinkResolver.Resolution( "Kubernetes", true ), r.resolve( "k8s  platform" ) );
    }

    @Test
    void aliasSharedByTwoPagesResolvesToTheLexicographicallyLowest() {
        final var r = resolver( Set.of( "Zeta", "Alpha" ),
                lookup( entry( "Zeta", "Shared Alias" ), entry( "Alpha", "Shared Alias" ) ) );
        assertEquals( "Alpha", r.resolve( "shared alias" ).pageName() );
    }

    @Test
    void caseCollisionResolvesToTheLexicographicallyLowest() {
        final var r = resolver( Set.of(), lookup( entry( "foo" ), entry( "Foo" ) ) );
        assertEquals( "Foo", r.resolve( "FOO" ).pageName() );
    }

    @Test
    void indexNotReadyFallsBackToExactThenCleanedName() {
        final var r = resolver( Set.of( "Alpha" ), null );
        assertEquals( new WikiLinkResolver.Resolution( "Foo bar", false ), r.resolve( "foo bar" ) );
        assertTrue( r.resolve( "Alpha" ).exists() );
    }

    @Test
    void providerFailureIsLoggedNotThrown() {
        final var r = new WikiLinkResolver( n -> { throw new ProviderException( "boom" ); }, Optional::empty );
        assertFalse( r.resolve( "X" ).exists() );
    }

    @Test
    void blankTargetIsNotFound() {
        assertFalse( resolver( Set.of(), null ).resolve( "  " ).exists() );
    }

    @Test
    void throwingTitleSupplierBehavesAsIndexNotReady() {
        final var r = new WikiLinkResolver( n -> "Alpha".equals( n ) ? n : null,
                () -> { throw new IllegalStateException( "boom" ); } );
        assertEquals( new WikiLinkResolver.Resolution( "Alpha", true ), r.resolve( "Alpha" ) );
        assertEquals( new WikiLinkResolver.Resolution( "Foo bar", false ), r.resolve( "foo bar" ) );
    }

    @Test
    void throwingExactLookupIsTreatedAsMiss() {
        final var r = new WikiLinkResolver( n -> { throw new IllegalStateException( "boom" ); }, Optional::empty );
        assertEquals( new WikiLinkResolver.Resolution( "Foo", false ), r.resolve( "foo" ) );
    }

    @Test
    void pageNameBeatsAnotherPagesAliasWithSamePhrase() {
        final var r = resolver( Set.of( "A", "Beta" ),
                lookup( entry( "A", "beta" ), entry( "Beta" ) ) );
        assertEquals( "Beta", r.resolve( "beta" ).pageName() );
    }
}
