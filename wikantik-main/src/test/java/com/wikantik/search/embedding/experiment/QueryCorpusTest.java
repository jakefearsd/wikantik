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
package com.wikantik.search.embedding.experiment;

import com.wikantik.search.embedding.experiment.QueryCorpus.EvalQuery;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class QueryCorpusTest {

    @Test
    void parsesHeaderAndRows() {
        final String csv = """
            query,ideal_page,notes
            how do I log in,Login,specific
            troubleshoot search,SearchTips,indirect
            """;
        final List< EvalQuery > rows = QueryCorpus.parseString( csv );
        assertEquals( 2, rows.size() );
        assertEquals( new EvalQuery( "how do I log in", "Login", "specific" ), rows.get( 0 ) );
        assertEquals( new EvalQuery( "troubleshoot search", "SearchTips", "indirect" ), rows.get( 1 ) );
    }

    @Test
    void skipsCommentAndBlankLines() {
        final String csv = """
            # this is a comment
            query,ideal_page,notes

            # another

            what is the uptime,UptimeFAQ,specific
            """;
        final List< EvalQuery > rows = QueryCorpus.parseString( csv );
        assertEquals( 1, rows.size() );
        assertEquals( "what is the uptime", rows.get( 0 ).query() );
    }

    @Test
    void handlesQuotedFieldsWithCommas() {
        final String csv = """
            query,ideal_page,notes
            "how, exactly, does it work",HowItWorks,indirect
            """;
        final List< EvalQuery > rows = QueryCorpus.parseString( csv );
        assertEquals( 1, rows.size() );
        assertEquals( "how, exactly, does it work", rows.get( 0 ).query() );
        assertEquals( "HowItWorks", rows.get( 0 ).idealPage() );
    }

    @Test
    void handlesEscapedDoubleQuotes() {
        final String csv = "query,ideal_page,notes\n"
            + "\"say \"\"hello\"\"\",GreetingPage,specific\n";
        final List< EvalQuery > rows = QueryCorpus.parseString( csv );
        assertEquals( 1, rows.size() );
        assertEquals( "say \"hello\"", rows.get( 0 ).query() );
    }

    @Test
    void rejectsRowWithTooFewColumns() {
        final String csv = """
            query,ideal_page,notes
            only_one_field,missing_columns
            """;
        assertThrows( java.io.IOException.class,
            () -> QueryCorpus.parse( new java.io.StringReader( csv ) ) );
    }

    @Test
    void emptyInputYieldsEmptyList() {
        assertEquals( List.of(), QueryCorpus.parseString( "" ) );
    }

    @Test
    void splitCsvTrimsWhitespace() {
        assertEquals( List.of( "a", "b", "c" ), QueryCorpus.splitCsv( "a,  b  ,c" ) );
    }

    @Test
    void splitCsvProducesEmptyTrailingFieldOnTrailingComma() {
        assertEquals( List.of( "a", "b", "" ), QueryCorpus.splitCsv( "a,b," ) );
    }
}
