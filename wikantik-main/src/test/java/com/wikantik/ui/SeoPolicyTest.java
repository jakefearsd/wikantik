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
package com.wikantik.ui;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SeoPolicyTest {

    private static SeoPolicy policy( final String noindex, final String gone ) {
        final Properties p = new Properties();
        p.setProperty( SeoPolicy.PROP_NOINDEX_CLUSTERS, noindex );
        p.setProperty( SeoPolicy.PROP_GONE_PAGES, gone );
        return SeoPolicy.fromProperties( p );
    }

    @Test
    void blankAndNullDisableEverything() {
        assertFalse( policy( "", "" ).isNoindex( "anything" ) );
        assertFalse( policy( "", "" ).isGone( "Foo" ) );
        assertFalse( SeoPolicy.fromProperties( null ).isNoindex( "anything" ) );
        assertFalse( SeoPolicy.none().hasNoindexClusters() );
    }

    @Test
    void exactClusterAndSubClusterMatch() {
        final SeoPolicy p = policy( " ml , ops/runbooks ", "" );
        assertTrue( p.isNoindex( "ml" ) );
        assertTrue( p.isNoindex( "ml/mlops" ), "sub-cluster of a listed parent" );
        assertTrue( p.isNoindex( "ops/runbooks" ) );
        assertFalse( p.isNoindex( "ops" ), "parent of a listed sub-cluster is not listed" );
    }

    @Test
    void fingerprintIsEmptyWithoutNoindexClustersAndTracksTheConfiguredSet() {
        assertEquals( "", SeoPolicy.none().noindexFingerprint() );
        assertEquals( "", policy( "", "Gone1" ).noindexFingerprint(), "gone pages never affect page validators" );
        final String a = policy( "ml, ops", "" ).noindexFingerprint();
        assertFalse( a.isEmpty() );
        assertEquals( a, policy( " ops ,ml", "" ).noindexFingerprint(), "order and spacing must not matter" );
        assertNotEquals( a, policy( "ml", "" ).noindexFingerprint() );
    }

    @Test
    void siblingSharingStringPrefixIsNotMatched() {
        assertFalse( policy( "ml", "" ).isNoindex( "ml-ops" ) );
    }

    @Test
    void onlyThePrimaryMembershipCounts() {
        final SeoPolicy p = policy( "ml", "" );
        assertTrue( p.isNoindex( List.of( "ml", "other" ) ) );
        assertFalse( p.isNoindex( List.of( "other", "ml" ) ) );
        assertFalse( p.isNoindex( null ) );
    }

    @Test
    void metadataConvenience() {
        final SeoPolicy p = policy( "ml", "" );
        assertTrue( p.isNoindexMetadata( Map.of( "cluster", "ml" ) ) );
        assertFalse( p.isNoindexMetadata( Map.of() ) );
        assertFalse( p.isNoindexMetadata( null ) );
    }

    @Test
    void gonePagesAreExactSlugs() {
        final SeoPolicy p = policy( "", "OldPage, Other" );
        assertTrue( p.isGone( "OldPage" ) );
        assertTrue( p.isGone( "Other" ) );
        assertFalse( p.isGone( "OldPage2" ) );
        assertFalse( p.isGone( null ) );
    }
}
