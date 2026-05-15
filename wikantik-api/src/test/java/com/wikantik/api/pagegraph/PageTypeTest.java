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
package com.wikantik.api.pagegraph;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PageTypeTest {

    @Test
    void fromFrontmatterParsesEveryKnownType() {
        assertEquals( PageType.HUB, PageType.fromFrontmatter( "hub" ) );
        assertEquals( PageType.ARTICLE, PageType.fromFrontmatter( "article" ) );
        assertEquals( PageType.REFERENCE, PageType.fromFrontmatter( "reference" ) );
        assertEquals( PageType.RUNBOOK, PageType.fromFrontmatter( "runbook" ) );
        assertEquals( PageType.DESIGN, PageType.fromFrontmatter( "design" ) );
    }

    @Test
    void fromFrontmatterTrimsAndLowercases() {
        assertEquals( PageType.HUB, PageType.fromFrontmatter( "  HUB " ) );
        assertEquals( PageType.RUNBOOK, PageType.fromFrontmatter( "Runbook" ) );
    }

    @Test
    void fromFrontmatterUnknownForNullOrUnrecognized() {
        assertEquals( PageType.UNKNOWN, PageType.fromFrontmatter( null ) );
        assertEquals( PageType.UNKNOWN, PageType.fromFrontmatter( "wiki" ) );
        assertEquals( PageType.UNKNOWN, PageType.fromFrontmatter( 42 ) );
    }

    @Test
    void asFrontmatterValueIsLowercaseName() {
        assertEquals( "hub", PageType.HUB.asFrontmatterValue() );
        assertEquals( "unknown", PageType.UNKNOWN.asFrontmatterValue() );
    }
}
