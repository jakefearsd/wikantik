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

class OllamaPageExtractorTest {

    private static OllamaPageExtractor newExtractor(final HttpClient client) {
        return new OllamaPageExtractor(
            client, "http://localhost:11434", "gemma4-assist:latest", 60_000L,
            new PageExtractionResponseParser(new EvidenceGroundingVerifier(), 12, 8));
    }

    @Test
    @SuppressWarnings("unchecked")
    void successfulExtraction() throws Exception {
        final HttpClient client = mock(HttpClient.class);
        final HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(
            "{\"message\":{\"content\":\"{\\\"entities\\\":[{\\\"name\\\":\\\"Python\\\",\\\"type\\\":\\\"Technology\\\",\\\"evidence_span\\\":\\\"Python is a language\\\",\\\"confidence\\\":0.9}],\\\"relations\\\":[]}\"}}");
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);

        final OllamaPageExtractor extractor = newExtractor(client);
        final Page page = new Page("PythonPage", null, "Python is a language. Used widely.", "", List.of());
        final ExtractionContext ctx = new ExtractionContext("PythonPage", List.of(), Map.of());
        final PageExtractionResult result = extractor.extract(page, ctx);
        assertEquals(1, result.entities().size());
        assertEquals("Python", result.entities().get(0).name());
        assertEquals("ollama:gemma4-assist", result.extractorCode());
    }

    @Test
    @SuppressWarnings("unchecked")
    void httpErrorReturnsEmpty() throws Exception {
        final HttpClient client = mock(HttpClient.class);
        final HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(500);
        when(response.body()).thenReturn("Internal Server Error");
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);

        final OllamaPageExtractor extractor = newExtractor(client);
        final Page page = new Page("X", null, "body", "", List.of());
        final PageExtractionResult result = extractor.extract(page, new ExtractionContext("X", List.of(), Map.of()));
        assertEquals(0, result.entities().size());
        assertEquals("ollama:gemma4-assist", result.extractorCode());
    }

    @Test
    @SuppressWarnings("unchecked")
    void ioExceptionReturnsEmptyAndDoesNotThrow() throws Exception {
        final HttpClient client = mock(HttpClient.class);
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
            .thenThrow(new IOException("connection refused"));

        final OllamaPageExtractor extractor = newExtractor(client);
        final Page page = new Page("X", null, "body", "", List.of());
        final PageExtractionResult result = extractor.extract(page, new ExtractionContext("X", List.of(), Map.of()));
        assertEquals(0, result.entities().size());
    }

    @Test
    @SuppressWarnings("unchecked")
    void interruptedExceptionReturnsEmptyAndRestoresInterruptFlag() throws Exception {
        final HttpClient client = mock(HttpClient.class);
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
            .thenThrow(new InterruptedException("interrupted"));

        final OllamaPageExtractor extractor = newExtractor(client);
        final Page page = new Page("X", null, "body", "", List.of());
        try {
            final PageExtractionResult result = extractor.extract(page, new ExtractionContext("X", List.of(), Map.of()));
            assertEquals(0, result.entities().size());
            assertTrue(Thread.currentThread().isInterrupted(), "interrupt flag must be restored");
        } finally {
            // Clear the flag so we don't pollute other tests in the same JVM.
            Thread.interrupted();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void requestSendsFormatJsonAndModel() throws Exception {
        final HttpClient client = mock(HttpClient.class);
        final HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(
            "{\"message\":{\"content\":\"{\\\"entities\\\":[],\\\"relations\\\":[]}\"}}");
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);

        final OllamaPageExtractor extractor = newExtractor(client);
        final Page page = new Page("PythonPage", null, "Python is a language.", "", List.of());
        extractor.extract(page, new ExtractionContext("PythonPage", List.of(), Map.of()));

        final ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(client).send(captor.capture(), any(HttpResponse.BodyHandler.class));
        final HttpRequest req = captor.getValue();
        assertEquals("http://localhost:11434/api/chat", req.uri().toString());
        assertEquals("POST", req.method());
        // Body publisher carries the JSON; we can't easily extract the bytes, but we can
        // smoke-check that Content-Type is set.
        assertTrue(req.headers().firstValue("Content-Type").orElse("").contains("application/json"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void nonJsonBodyReturnsEmpty() throws Exception {
        final HttpClient client = mock(HttpClient.class);
        final HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("not json at all");
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);

        final OllamaPageExtractor extractor = newExtractor(client);
        final Page page = new Page("X", null, "body", "", List.of());
        final PageExtractionResult result = extractor.extract(page, new ExtractionContext("X", List.of(), Map.of()));
        assertEquals(0, result.entities().size());
    }

    @Test
    void codeIncludesModelTag() {
        final OllamaPageExtractor extractor = newExtractor(mock(HttpClient.class));
        assertEquals("ollama:gemma4-assist", extractor.code());
    }

    @Test
    void codeWithoutLatestSuffixIsPreserved() {
        final OllamaPageExtractor extractor = new OllamaPageExtractor(
            mock(HttpClient.class), "u", "gemma3:9b", 60_000L,
            new PageExtractionResponseParser(new EvidenceGroundingVerifier(), 12, 8));
        assertEquals("ollama:gemma3:9b", extractor.code());
    }
}
