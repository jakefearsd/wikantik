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
package com.wikantik.ontology.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import com.wikantik.api.managers.PageManager;
import com.wikantik.ontology.projection.PageRecord;
import com.wikantik.pagegraph.spine.PageCanonicalIdsDao;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith( MockitoExtension.class )
class PageRecordBuilderTest {

    @Mock PageManager pageManager;

    @Test
    void buildsPageRecordsWithFrontmatterEnrichment() {
        final PageCanonicalIdsDao.Row row = new PageCanonicalIdsDao.Row(
                "01CANON0000000000000000001", "GraphDb", "Graph DB", "article", "graph-databases",
                Instant.EPOCH, Instant.EPOCH );
        final String pageText = """
                ---
                tags: [databases, nosql]
                summary: A page about graph databases
                date: 2026-03-14
                author: claude-code-researcher
                ---
                Body text.
                """;
        when( pageManager.getPureText( "GraphDb", -1 ) ).thenReturn( pageText );

        final PageRecordBuilder builder = new PageRecordBuilder( pageManager, () -> List.of( row ) );
        final List< PageRecord > records = builder.build();

        assertEquals( 1, records.size() );
        final PageRecord r = records.get( 0 );
        assertEquals( "01CANON0000000000000000001", r.canonicalId() );
        assertEquals( "GraphDb", r.slug() );
        assertEquals( "article", r.type() );
        assertEquals( "graph-databases", r.cluster() );
        assertEquals( List.of( "databases", "nosql" ), r.tags() );
        assertEquals( "A page about graph databases", r.summary() );
        assertEquals( "2026-03-14", r.isoDate() );
        assertEquals( "claude-code-researcher", r.author() );
    }

    @Test
    void toleratesMissingFrontmatter() {
        final PageCanonicalIdsDao.Row row = new PageCanonicalIdsDao.Row(
                "01CANON0000000000000000002", "Bare", "Bare", "article", null, Instant.EPOCH, Instant.EPOCH );
        when( pageManager.getPureText( "Bare", -1 ) ).thenReturn( "no frontmatter here" );
        final PageRecordBuilder builder = new PageRecordBuilder( pageManager, () -> List.of( row ) );
        final PageRecord r = builder.build().get( 0 );
        assertEquals( List.of(), r.tags() );
        assertEquals( null, r.summary() );
    }

    @Test
    void fromRowEnrichesASingleRow() {
        final PageCanonicalIdsDao.Row row = new PageCanonicalIdsDao.Row(
                "01CANON0000000000000000009", "Solo", "Solo", "article", "ml",
                java.time.Instant.EPOCH, java.time.Instant.EPOCH );
        when( pageManager.getPureText( "Solo", -1 ) )
                .thenReturn( "---\ntags: [x]\nsummary: s\n---\nbody" );
        final PageRecord r = PageRecordBuilder.fromRow( row, pageManager );
        assertEquals( "01CANON0000000000000000009", r.canonicalId() );
        assertEquals( List.of( "x" ), r.tags() );
        assertEquals( "s", r.summary() );
        assertEquals( "ml", r.cluster() );
    }
}
