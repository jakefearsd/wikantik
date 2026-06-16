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
import com.wikantik.api.knowledge.ExtractionContext;
import com.wikantik.api.knowledge.Page;
import com.wikantik.api.knowledge.PageExtractionResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Mirror of {@link OllamaPageExtractorTest} for the Anthropic-backed page extractor.
 * Reuses the same {@link PageExtractionPromptBuilder} prompts + {@link PageExtractionResponseParser}
 * so a Claude vs Ollama A/B holds everything but the model constant.
 */
class ClaudePageExtractorTest {

    private static final Gson GSON = new Gson();

    private static ClaudePageExtractor newExtractor(final HttpClient client) {
        return new ClaudePageExtractor(
            "test-key", "claude-sonnet-4-6", 60_000L, client,
            new PageExtractionResponseParser(new EvidenceGroundingVerifier(), 12, 8));
    }

    /** Build a well-formed Anthropic Messages response whose single text block carries {@code innerJson}. */
    private static String anthropicBody(final String innerJson) {
        return GSON.toJson(Map.of(
            "content", List.of(Map.of("type", "text", "text", innerJson))));
    }

    @Test
    @SuppressWarnings("unchecked")
    void successfulExtraction() throws Exception {
        final HttpClient client = mock(HttpClient.class);
        final HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(anthropicBody(
            "{\"entities\":[{\"name\":\"Python\",\"type\":\"Technology\","
            + "\"evidence_span\":\"Python is a language\",\"confidence\":0.9}],\"relations\":[]}"));
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);

        final ClaudePageExtractor extractor = newExtractor(client);
        final Page page = new Page("PythonPage", null, "Python is a language. Used widely.", "", List.of());
        final ExtractionContext ctx = new ExtractionContext("PythonPage", List.of(), Map.of());
        final PageExtractionResult result = extractor.extract(page, ctx);
        assertEquals(1, result.entities().size());
        assertEquals("Python", result.entities().get(0).name());
        assertEquals("claude:claude-sonnet-4-6", result.extractorCode());
    }

    @Test
    @SuppressWarnings("unchecked")
    void httpErrorReturnsEmpty() throws Exception {
        final HttpClient client = mock(HttpClient.class);
        final HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(429);
        when(response.body()).thenReturn("rate limited");
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);

        final ClaudePageExtractor extractor = newExtractor(client);
        final Page page = new Page("X", null, "body", "", List.of());
        final PageExtractionResult result = extractor.extract(page, new ExtractionContext("X", List.of(), Map.of()));
        assertEquals(0, result.entities().size());
        assertEquals("claude:claude-sonnet-4-6", result.extractorCode());
    }

    @Test
    @SuppressWarnings("unchecked")
    void ioExceptionReturnsEmptyAndDoesNotThrow() throws Exception {
        final HttpClient client = mock(HttpClient.class);
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
            .thenThrow(new IOException("connection refused"));

        final ClaudePageExtractor extractor = newExtractor(client);
        final Page page = new Page("X", null, "body", "", List.of());
        final PageExtractionResult result = extractor.extract(page, new ExtractionContext("X", List.of(), Map.of()));
        assertEquals(0, result.entities().size());
    }

    @Test
    void missingApiKeyReturnsEmptyWithoutHttpCall() throws Exception {
        final HttpClient client = mock(HttpClient.class);
        final ClaudePageExtractor extractor = new ClaudePageExtractor(
            "  ", "claude-sonnet-4-6", 60_000L, client,
            new PageExtractionResponseParser(new EvidenceGroundingVerifier(), 12, 8));
        final Page page = new Page("X", null, "body", "", List.of());
        final PageExtractionResult result = extractor.extract(page, new ExtractionContext("X", List.of(), Map.of()));
        assertEquals(0, result.entities().size());
        verify(client, never()).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void requestTargetsAnthropicWithAuthHeaders() throws Exception {
        final HttpClient client = mock(HttpClient.class);
        final HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(anthropicBody("{\"entities\":[],\"relations\":[]}"));
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);

        final ClaudePageExtractor extractor = newExtractor(client);
        final Page page = new Page("PythonPage", null, "Python is a language.", "", List.of());
        extractor.extract(page, new ExtractionContext("PythonPage", List.of(), Map.of()));

        final ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(client).send(captor.capture(), any(HttpResponse.BodyHandler.class));
        final HttpRequest req = captor.getValue();
        assertEquals("https://api.anthropic.com/v1/messages", req.uri().toString());
        assertEquals("POST", req.method());
        assertEquals("test-key", req.headers().firstValue("x-api-key").orElse(""));
        assertTrue(req.headers().firstValue("anthropic-version").isPresent(), "anthropic-version header required");
        assertTrue(req.headers().firstValue("Content-Type").orElse("").contains("application/json"));
    }

    @Test
    void codeIncludesModelTag() {
        final ClaudePageExtractor extractor = newExtractor(mock(HttpClient.class));
        assertEquals("claude:claude-sonnet-4-6", extractor.code());
    }
}
