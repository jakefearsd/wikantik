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
package com.wikantik.util;

import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class WikiPageUrlsTest {

    private static Properties baseUrl( final String value ) {
        final Properties props = new Properties();
        props.setProperty( "wikantik.baseURL", value );
        return props;
    }

    @Test
    void baseIsTheConfiguredOriginWithoutItsTrailingSlash() {
        assertEquals( "https://wiki.example.com", WikiPageUrls.base( baseUrl( "https://wiki.example.com/" ), "" ) );
        assertEquals( "https://wiki.example.com", WikiPageUrls.base( baseUrl( " https://wiki.example.com " ), "" ) );
    }

    @Test
    void baseFallsBackWhenNoOriginIsConfigured() {
        assertEquals( "/ctx", WikiPageUrls.base( baseUrl( "  " ), "/ctx" ) );
        assertEquals( "/ctx", WikiPageUrls.base( new Properties(), "/ctx" ) );
        assertEquals( "", WikiPageUrls.base( null, null ) );
    }

    @Test
    void pageLinksThroughTheReaderRoute() {
        assertEquals( "https://wiki.example.com/wiki/HybridRetrieval",
            WikiPageUrls.page( "https://wiki.example.com", "HybridRetrieval" ) );
        assertEquals( "/wiki/HybridRetrieval", WikiPageUrls.page( "", "HybridRetrieval" ) );
    }

    @Test
    void pageEncodesTheNameAsOnePathSegment() {
        assertEquals( "/wiki/Foo%20Bar%2FBaz", WikiPageUrls.page( "", "Foo Bar/Baz" ) );
    }
}
