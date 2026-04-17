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
package com.wikantik.knowledge.chunking;

import com.wikantik.api.frontmatter.ParsedPage;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

public class ContentChunker {

    public record Config(int targetTokens, int maxTokens, int minTokens) {}

    private final Config config;

    public ContentChunker(Config config) {
        this.config = config;
    }

    public List<Chunk> chunk(String pageName, ParsedPage page) {
        String body = page.body() == null ? "" : page.body().strip();
        List<Chunk> out = new ArrayList<>();
        if (body.isEmpty()) {
            return out;
        }
        List<String> headingPath = List.of();
        out.add(buildChunk(pageName, 0, headingPath, body));
        return out;
    }

    Chunk buildChunk(String pageName, int index, List<String> headingPath, String text) {
        int charCount = text.length();
        int tokenCountEstimate = (int) Math.ceil(charCount / 4.0);
        String hash = sha256Hex16(String.join("\u0000", headingPath) + "\n" + text);
        return new Chunk(pageName, index, headingPath, text,
                         charCount, tokenCountEstimate, hash);
    }

    private static String sha256Hex16(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
