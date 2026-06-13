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
import org.junit.jupiter.api.Test;
import java.io.StringReader;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class BundleCorpusLoaderTest {

    @Test
    void groups_rows_by_query_id_and_parses_heading_path() {
        final String csv = String.join( "\n",
            "# comment line",
            "query_id,query,category,gold_canonical_id,gold_heading_path,notes",
            "q1,how do I deploy,SIMILARITY,01DEP,Deploy>Local,seed",
            "q1,how do I deploy,SIMILARITY,01DEP,Deploy>Docker,seed",
            "q2,what uses the SHACL gate,RELATIONAL,01ONT,Validation,relational" );

        final List<BundleEvalQuestion> qs = BundleCorpusLoader.parse( new StringReader( csv ) );

        assertEquals( 2, qs.size() );
        final BundleEvalQuestion q1 = qs.stream().filter( q -> q.queryId().equals("q1") ).findFirst().orElseThrow();
        assertEquals( BundleCategory.SIMILARITY, q1.category() );
        assertEquals( 2, q1.goldSections().size() );
        assertEquals( List.of( "Deploy", "Local" ), q1.goldSections().get( 0 ).headingPath() );
        final BundleEvalQuestion q2 = qs.stream().filter( q -> q.queryId().equals("q2") ).findFirst().orElseThrow();
        assertEquals( BundleCategory.RELATIONAL, q2.category() );
    }

    @Test
    void rejects_unknown_category() {
        final String csv = String.join( "\n",
            "query_id,query,category,gold_canonical_id,gold_heading_path,notes",
            "q1,x,NONSENSE,01A,Setup,n" );
        assertThrows( IllegalArgumentException.class,
            () -> BundleCorpusLoader.parse( new StringReader( csv ) ) );
    }
}
