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
package com.wikantik.knowledge.bundle;

import com.wikantik.api.knowledge.RetrievalResult;
import com.wikantik.api.knowledge.RetrievedChunk;
import com.wikantik.api.knowledge.RetrievedPage;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SectionAssemblerTest {
    private static RetrievedChunk chunk( List<String> path, String text, double score ) {
        return new RetrievedChunk( path, text, score, List.of() );
    }

    @Test
    void groups_by_heading_and_keeps_top_s_per_page_with_best_chunk() {
        final RetrievedPage page = new RetrievedPage(
            "DeployGuide", "/wiki/DeployGuide", 1.0, "", "ops", List.of(),
            List.of(
                chunk( List.of( "Setup" ), "setup A", 0.5 ),
                chunk( List.of( "Setup" ), "setup B (best)", 0.9 ),   // same section, higher score
                chunk( List.of( "Usage" ), "usage", 0.7 ),
                chunk( List.of( "Notes" ), "notes", 0.1 ) ),
            List.of(), "admin", null );

        final List<CandidateSection> secs = new SectionAssembler( 2 )
            .assemble( new RetrievalResult( "q", List.of( page ), 1 ) );

        // top-2 sections per page by best-chunk score: Setup(0.9), Usage(0.7); Notes dropped
        assertEquals( 2, secs.size() );
        assertEquals( List.of( "Setup" ), secs.get( 0 ).headingPath() );
        assertEquals( "setup B (best)", secs.get( 0 ).text() );
        assertEquals( 0.9, secs.get( 0 ).denseScore(), 1e-9 );
        assertEquals( List.of( "Usage" ), secs.get( 1 ).headingPath() );
    }
}
