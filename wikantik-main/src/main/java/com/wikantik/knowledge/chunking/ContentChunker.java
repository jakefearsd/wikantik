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

import com.vladsch.flexmark.ast.Heading;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Node;
import com.wikantik.api.frontmatter.ParsedPage;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
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

        Parser parser = Parser.builder().build();
        Node root = parser.parse(body);

        Deque<String> headingStack = new ArrayDeque<>();
        List<Node> pendingBlocks = new ArrayList<>();
        int[] chunkIndex = {0};

        for (Node child : root.getChildren()) {
            if (child instanceof Heading heading) {
                flushChunk(pageName, chunkIndex, List.copyOf(headingStack),
                           pendingBlocks, out);
                adjustHeadingStack(headingStack, heading.getLevel(),
                                   heading.getText().toString());
            } else {
                pendingBlocks.add(child);
            }
        }
        flushChunk(pageName, chunkIndex, List.copyOf(headingStack), pendingBlocks, out);
        return out;
    }

    private void adjustHeadingStack(Deque<String> stack, int level, String title) {
        while (stack.size() >= level) {
            stack.removeLast();
        }
        while (stack.size() < level - 1) {
            stack.addLast("");
        }
        stack.addLast(title.trim());
    }

    private void flushChunk(String pageName, int[] idx, List<String> headingPath,
                            List<Node> blocks, List<Chunk> out) {
        if (blocks.isEmpty()) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (Node block : blocks) {
            sb.append(block.getChars().toString()).append("\n\n");
        }
        String text = sb.toString().strip();
        blocks.clear();
        if (text.isEmpty()) {
            return;
        }
        out.add(buildChunk(pageName, idx[0]++, headingPath, text));
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
