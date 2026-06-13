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
package com.wikantik.knowledge.eval;

import com.wikantik.api.eval.BundleCategory;
import com.wikantik.api.eval.BundleEvalQuestion;
import com.wikantik.api.eval.BundleSection;
import com.wikantik.api.eval.GoldSection;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class BundleEvalRunnerTest {

    @Test
    void aggregates_recall_overall_and_per_category() {
        final List<BundleEvalQuestion> corpus = List.of(
            new BundleEvalQuestion( "q1", "deploy", BundleCategory.SIMILARITY,
                List.of( new GoldSection( "01A", List.of( "Setup" ) ) ) ),
            new BundleEvalQuestion( "q2", "what uses X", BundleCategory.RELATIONAL,
                List.of( new GoldSection( "01B", List.of( "Uses" ) ) ) ) );

        // q1 retrieves its gold (recall 1.0); q2 retrieves noise (recall 0.0).
        final BundleEvalRunner.BundleRetriever retriever = query -> {
            if ( query.contains( "deploy" ) )
                return List.of( new BundleSection( "01A", List.of( "Setup" ), "..." ) );
            return List.of( new BundleSection( "01Z", List.of( "Noise" ), "..." ) );
        };

        final BundleEvalReport report = new BundleEvalRunner( retriever, 5 ).run( corpus );

        assertEquals( 0.5, report.overallRecall(), 1e-9 );
        assertEquals( 1.0, report.recallByCategory().get( BundleCategory.SIMILARITY ), 1e-9 );
        assertEquals( 0.0, report.recallByCategory().get( BundleCategory.RELATIONAL ), 1e-9 );
        assertEquals( 2, report.questionsScored() );
    }

    @Test
    void constructor_rejects_non_positive_precisionK() {
        final BundleEvalRunner.BundleRetriever retriever = q -> List.of();
        assertThrows( IllegalArgumentException.class,
            () -> new BundleEvalRunner( retriever, 0 ) );
    }
}
