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
package com.wikantik.knowledge.extraction;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OllamaChatRequestTest {

    @Test
    void body_disables_thinking_and_forces_json_stream_off() {
        final Map< String, Object > body = OllamaChatRequest.body( "gemma4-graph:12b", "sys", "usr", null );

        // The whole point: thinking OFF. Reasoning models otherwise emit a chain-of-thought
        // that breaks structured-JSON extraction (invalid/truncated output) and runs 10-20x slower.
        assertEquals( Boolean.FALSE, body.get( "think" ),
            "extraction must send think:false — thinking is a request-only control" );
        assertEquals( "json", body.get( "format" ) );
        assertEquals( Boolean.FALSE, body.get( "stream" ) );
        assertEquals( "gemma4-graph:12b", body.get( "model" ) );
        assertFalse( body.containsKey( "keep_alive" ), "keep_alive omitted when null" );

        final Object messages = body.get( "messages" );
        assertTrue( messages instanceof List< ? > l && l.size() == 2, "system + user messages" );
    }

    @Test
    void serialized_json_carries_think_false() {
        final String json = new Gson().toJson( OllamaChatRequest.body( "m", "s", "u", "30m" ) );
        assertTrue( json.contains( "\"think\":false" ), "serialized body must contain think:false; was: " + json );
        assertTrue( json.contains( "\"keep_alive\":\"30m\"" ), "keep_alive included when provided" );
    }
}
