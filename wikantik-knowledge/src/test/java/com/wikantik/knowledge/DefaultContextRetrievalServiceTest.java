/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package com.wikantik.knowledge;

import com.wikantik.api.knowledge.RetrievedPage;
import com.wikantik.knowledge.testfakes.FakeDeps;
import com.wikantik.knowledge.testfakes.FakePageManager;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;

class DefaultContextRetrievalServiceTest {

    @Test
    void getPage_returnsNullWhenMissing() {
        final DefaultContextRetrievalService svc = FakeDeps.minimal().build();
        assertNull( svc.getPage( "Nonexistent" ) );
    }

    @Test
    void getPage_returnsShapedRecord() {
        final FakePageManager pm = new FakePageManager();
        pm.addPage( "Hub", "---\nsummary: the hub\ncluster: search\n"
                + "tags: [retrieval, search]\n---\n\nBody", "alice", Date.from( Instant.parse( "2026-04-23T00:00:00Z" ) ) );
        final DefaultContextRetrievalService svc = FakeDeps.minimal()
            .pageManager( pm ).baseUrl( "https://wiki.example" ).build();

        final RetrievedPage p = svc.getPage( "Hub" );

        assertNotNull( p );
        assertEquals( "Hub", p.name() );
        assertEquals( "https://wiki.example/Hub", p.url() );
        assertEquals( "the hub", p.summary() );
        assertEquals( "search", p.cluster() );
        assertEquals( java.util.List.of( "retrieval", "search" ), p.tags() );
        assertEquals( 0.0, p.score() );
        assertTrue( p.contributingChunks().isEmpty() );
        assertTrue( p.relatedPages().isEmpty() );
        assertEquals( "alice", p.author() );
        assertEquals( java.util.Date.from( java.time.Instant.parse( "2026-04-23T00:00:00Z" ) ),
            p.lastModified() );
    }
}
