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
package com.wikantik.ontology;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.function.Predicate;

import com.wikantik.ontology.projection.PageRecord;
import org.junit.jupiter.api.Test;

class PublicProjectionFilterTest {

    private final Predicate< String > isPublic = slug -> "PublicPage".equals( slug );

    @Test
    void publicPagesKeepOnlyViewable() {
        final PageRecord pubP = new PageRecord( "C1", "PublicPage", "T", "article", "ml", List.of(), null, null, null );
        final PageRecord secP = new PageRecord( "C2", "SecretPage", "T", "article", "ml", List.of(), null, null, null );
        final List< PageRecord > pub = PublicProjectionFilter.publicPages( List.of( pubP, secP ), isPublic );
        assertEquals( 1, pub.size() );
        assertEquals( "PublicPage", pub.get( 0 ).slug() );
    }
}
