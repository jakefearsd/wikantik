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
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.wikantik.api.knowledge.ExtractionContext;
import com.wikantik.api.knowledge.Page;
import com.wikantik.api.knowledge.PageExtractionResult;
import com.wikantik.api.knowledge.PageExtractor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;import java.util.Map;

/**
 * Per-page extractor backed by an Ollama /api/chat endpoint with
 * {@code format: "json"}. Mirrors the existing OllamaEntityExtractor style
 * (no retries, fail-open). Stripping ":latest" from the model tag matches
 * how the chunk extractor reports lineage.
 */
public final class OllamaPageExtractor implements PageExtractor {

    private static final Logger LOG = LogManager.getLogger(OllamaPageExtractor.class);
    private static final Gson GSON = new Gson();

    private final HttpClient httpClient;
    private final String baseUrl;
    private final String model;
    private final long timeoutMs;
    private final PageExtractionResponseParser parser;

    public OllamaPageExtractor(final HttpClient httpClient, final String baseUrl, final String model,
                                final long timeoutMs, final PageExtractionResponseParser parser) {
        this.httpClient = httpClient;
        this.baseUrl = baseUrl;
        this.model = model;
        this.timeoutMs = timeoutMs;
        this.parser = parser;
    }

    @Override
    public String code() {
        final String trimmed = model.trim();
        final String stripped = trimmed.endsWith(":latest")
            ? trimmed.substring(0, trimmed.length() - ":latest".length())
            : trimmed;
        return "ollama:" + stripped;
    }

    @Override
    public PageExtractionResult extract(final Page page, final ExtractionContext context) {
        final long started = System.nanoTime();
        try {
            final String raw = callOllama(page, context);
            final Duration latency = Duration.ofNanos(System.nanoTime() - started);
            if (raw == null) {
                return PageExtractionResult.empty(code(), page.name(), latency);
            }
            return parser.parse(raw, code(), page.name(), page.body(), latency);
        } catch (final InterruptedException ie) {
            Thread.currentThread().interrupt();
            LOG.warn("Ollama extraction interrupted for page '{}': {}", page.name(), ie.getMessage());
            return PageExtractionResult.empty(code(), page.name(), Duration.ofNanos(System.nanoTime() - started));
        } catch (final IOException | RuntimeException e) {
            LOG.warn("Ollama extraction failed for page '{}': {}", page.name(), e.getMessage());
            return PageExtractionResult.empty(code(), page.name(), Duration.ofNanos(System.nanoTime() - started));
        }
    }

    private String callOllama(final Page page, final ExtractionContext ctx) throws IOException, InterruptedException {
        final Map<String, Object> body = OllamaChatRequest.body(
            model, PageExtractionPromptBuilder.SYSTEM_PROMPT,
            PageExtractionPromptBuilder.buildUserPrompt(page, ctx), null );
        final String url = stripTrailingSlash(baseUrl) + "/api/chat";
        final HttpRequest req = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofMillis(timeoutMs))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)))
            .build();
        final HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() / 100 != 2) {
            LOG.warn("Ollama page extract HTTP {} for page '{}'", res.statusCode(), page.name());
            return null;
        }
        final JsonElement root;
        try {
            root = JsonParser.parseString(res.body());
        } catch (final RuntimeException e) {
            LOG.warn("Ollama page extract returned non-JSON body for '{}': {}", page.name(), e.getMessage());
            return null;
        }
        if (!root.isJsonObject()) return null;
        final JsonElement message = root.getAsJsonObject().get("message");
        if (message == null || !message.isJsonObject()) return null;
        final JsonElement content = message.getAsJsonObject().get("content");
        return content == null || content.isJsonNull() ? null : content.getAsString();
    }

    private static String stripTrailingSlash(final String s) {
        if (s == null || s.isEmpty()) return "";
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
