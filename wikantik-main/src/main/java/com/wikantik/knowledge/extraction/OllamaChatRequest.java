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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared builder for the Ollama {@code /api/chat} request body used by the
 * extraction and judge callers, so the non-negotiable {@code think:false} lives
 * in exactly one place and cannot drift between callers.
 *
 * <p><b>Why {@code think:false} is mandatory here.</b> Our graph-extraction model
 * ({@code gemma4-graph:12b}, a Gemma reasoning model) emits a chain-of-thought that
 * Ollama routes to a separate field. Left on, that trace breaks structured-JSON
 * extraction — it is high-variance and regularly produces invalid/truncated JSON —
 * and runs 10-20x slower. Thinking is a <i>request-only</i> control: Ollama rejects
 * {@code PARAMETER think} in a Modelfile ("unknown parameter"), so the model itself
 * cannot disable it; the client must send it on every request. {@code format:json}
 * alone is not a reliable substitute (it breaks down under heavy prompts).</p>
 */
final class OllamaChatRequest {

    private OllamaChatRequest() {}

    /**
     * Build the {@code /api/chat} request body. {@code keepAlive} (e.g. {@code "30m"})
     * is included only when non-null, so successive chunks of one page keep the model
     * resident; pass {@code null} to omit it.
     */
    static Map< String, Object > body( final String model,
                                       final String systemPrompt,
                                       final String userPrompt,
                                       final String keepAlive ) {
        final Map< String, Object > body = new LinkedHashMap<>();
        body.put( "model", model );
        body.put( "stream", false );
        body.put( "format", "json" );
        body.put( "think", false );
        if ( keepAlive != null ) {
            body.put( "keep_alive", keepAlive );
        }
        body.put( "messages", List.of(
            Map.of( "role", "system", "content", systemPrompt ),
            Map.of( "role", "user", "content", userPrompt )
        ) );
        return body;
    }
}
