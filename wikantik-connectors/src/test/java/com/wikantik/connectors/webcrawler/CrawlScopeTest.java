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
package com.wikantik.connectors.webcrawler;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class CrawlScopeTest {
    @Test void sameHostOnly() {
        CrawlScope s = new CrawlScope( "ex.com", true, null );
        assertTrue( s.inScope( "https://ex.com/x" ) );
        assertFalse( s.inScope( "https://other.com/x" ) );
        assertFalse( s.inScope( "mailto:a@b.c" ) );
        assertFalse( s.inScope( "javascript:void(0)" ) );
    }
    @Test void pathPrefixRestriction() {
        CrawlScope s = new CrawlScope( "ex.com", true, "/docs" );
        assertTrue( s.inScope( "https://ex.com/docs/page" ) );
        assertFalse( s.inScope( "https://ex.com/blog/page" ) );
    }
    @Test void crossHostAllowedWhenNotSameHostOnly() {
        assertTrue( new CrawlScope( "ex.com", false, null ).inScope( "https://other.com/x" ) );
    }
}
