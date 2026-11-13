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
package com.wikantik.api.parser;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A page made of link openers that never close must cost one pass, not one pass per opener: the scanner runs on
 * every save (reference manager) and a 256 KB body of {@code [} once cost over a minute.
 */
class MarkdownLinkScannerLinearCostTest {

    private static final int CHARS = 200_000;

    @ParameterizedTest
    @ValueSource( strings = { "[", "[a](", "![", "[a] " } )
    void findMarkdownLinksIsLinearInUnclosedOpeners( final String unit ) {
        final String body = unit.repeat( CHARS / unit.length() );
        assertTimeoutPreemptively( Duration.ofSeconds( 5 ), () -> MarkdownLinkScanner.findMarkdownLinks( body ) );
    }

    @ParameterizedTest
    @ValueSource( strings = { "[", "[a](", "![", "[a] " } )
    void scanAllIsLinearInUnclosedOpeners( final String unit ) {
        final String body = unit.repeat( CHARS / unit.length() );
        assertTimeoutPreemptively( Duration.ofSeconds( 5 ), () -> MarkdownLinkScanner.scanAll( body ) );
    }

    @ParameterizedTest
    @ValueSource( strings = { "[", "[a](", "![", "[a] " } )
    void aLinkAfterTheOpenersIsStillFound( final String unit ) {
        final String body = unit.repeat( CHARS / unit.length() ) + "\n) [text](TargetPage) tail";
        assertTimeoutPreemptively( Duration.ofSeconds( 5 ),
                () -> assertTrue( MarkdownLinkScanner.findMarkdownLinks( body ).contains( "TargetPage" ) ) );
    }
}
