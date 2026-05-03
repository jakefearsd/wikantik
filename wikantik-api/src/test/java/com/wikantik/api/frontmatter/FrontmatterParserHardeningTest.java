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
package com.wikantik.api.frontmatter;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies SnakeYAML LoaderOptions hardening: bombing payloads should be
 * rejected before they exhaust memory / CPU.
 *
 * <p>{@link FrontmatterParser#parse} swallows YAML errors (returns empty
 * metadata + body), so a successful "rejection" is "no metadata is parsed".
 * {@link FrontmatterParser#parseStrict} must throw {@link FrontmatterParseException}
 * on a YAML bomb so callers see why the page is being refused.
 */
class FrontmatterParserHardeningTest {

    /** Billion-laughs / YAML-bomb: each level expands the previous level fivefold. */
    private static final String YAML_BOMB =
        "---\n"
        + "a: &a [\"x\",\"x\",\"x\",\"x\",\"x\",\"x\",\"x\",\"x\",\"x\",\"x\"]\n"
        + "b: &b [*a,*a,*a,*a,*a,*a,*a,*a,*a,*a]\n"
        + "c: &c [*b,*b,*b,*b,*b,*b,*b,*b,*b,*b]\n"
        + "d: &d [*c,*c,*c,*c,*c,*c,*c,*c,*c,*c]\n"
        + "e: &e [*d,*d,*d,*d,*d,*d,*d,*d,*d,*d]\n"
        + "f: &f [*e,*e,*e,*e,*e,*e,*e,*e,*e,*e]\n"
        + "g: &g [*f,*f,*f,*f,*f,*f,*f,*f,*f,*f]\n"
        + "---\nbody\n";

    @Test
    void parse_rejects_yaml_bomb_with_empty_metadata() {
        // Lenient parse() must not OOM and must not return the expanded structure.
        // It logs the SnakeYAML rejection and returns empty metadata + the body.
        final ParsedPage parsed = FrontmatterParser.parse( YAML_BOMB );
        assertNotNull( parsed );
        assertTrue( parsed.metadata().isEmpty(),
            "YAML bomb must not yield populated metadata; got: " + parsed.metadata().keySet() );
    }

    @Test
    void parseStrict_throws_on_yaml_bomb() {
        // Strict parse must surface the SnakeYAML rejection so the agent sees a clear refusal.
        assertThrows( FrontmatterParseException.class,
            () -> FrontmatterParser.parseStrict( YAML_BOMB ) );
    }

    @Test
    void parseStrict_throws_on_duplicate_keys() {
        // Hardened LoaderOptions disable duplicate keys — agents writing the same
        // field twice get a clear error rather than silent last-write-wins.
        final String dup = "---\n"
            + "title: First\n"
            + "title: Second\n"
            + "---\nbody\n";
        assertThrows( FrontmatterParseException.class,
            () -> FrontmatterParser.parseStrict( dup ) );
    }
}
