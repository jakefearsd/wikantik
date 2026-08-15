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
package com.wikantik.extractcli;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CorpusDivergenceCliTest {

    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    private final PrintStream out = new PrintStream( buffer, true, StandardCharsets.UTF_8 );

    private String output() {
        out.flush();
        return buffer.toString( StandardCharsets.UTF_8 );
    }

    private static CorpusSnapshot snap( final String name, final String slug, final String cluster ) {
        return new CorpusSnapshot( name,
                Map.of( slug, new PageFacts( slug, "01H8G3Z1K6Q5W7P9X2V4R0T8MN", cluster, "article" ) ),
                List.of() );
    }

    @Test
    void agreeing_corpora_exit_zero() {
        final int code = CorpusDivergenceCli.run( snap( "repo", "A", "c1" ), snap( "prod", "A", "c1" ), true, out );
        assertEquals( 0, code );
        assertTrue( output().contains( "no divergence" ) );
    }

    @Test
    void divergence_without_check_reports_but_still_exits_zero() {
        final int code = CorpusDivergenceCli.run( snap( "repo", "A", "c1" ), snap( "prod", "A", "c2" ), false, out );
        assertEquals( 0, code );
        assertTrue( output().contains( "cluster" ) );
    }

    @Test
    void divergence_under_check_exits_one_so_ci_fails() {
        final int code = CorpusDivergenceCli.run( snap( "repo", "A", "c1" ), snap( "prod", "A", "c2" ), true, out );
        assertEquals( 1, code );
    }

    /**
     * An incomplete snapshot must produce a distinct exit code and no divergence report at
     * all: reporting findings from a partial corpus is precisely the failure this tool exists
     * to prevent.
     */
    @Test
    void an_incomplete_snapshot_exits_two_and_reports_no_findings() {
        final CorpusSnapshot partial = new CorpusSnapshot( "prod", Map.of(), List.of( "HTTP 502" ) );

        final int code = CorpusDivergenceCli.run( snap( "repo", "A", "c1" ), partial, true, out );

        assertEquals( 2, code );
        assertTrue( output().contains( "incomplete" ), "must explain the refusal: " + output() );
        assertFalse( output().contains( "only-in-repo" ),
                     "must not report findings derived from a partial corpus: " + output() );
    }
}
