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

import com.vladsch.flexmark.ast.BulletList;
import com.vladsch.flexmark.ast.FencedCodeBlock;
import com.vladsch.flexmark.ast.Heading;
import com.vladsch.flexmark.ast.OrderedList;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Node;
import com.wikantik.api.frontmatter.ParsedPage;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HexFormat;
import java.util.List;

public class ContentChunker {

    private static final Logger LOG = LogManager.getLogger(ContentChunker.class);

    /**
     * Chunker tuning. Only two knobs actually change behaviour:
     * <ul>
     *   <li>{@code maxTokens} — hard upper bound on a non-atomic chunk's size.
     *       When appending a block would exceed this, the current buffer is
     *       emitted first. Oversized atomic blocks (code/tables/lists within
     *       budget) pass through intact; oversized paragraphs fall back to
     *       sentence-level splitting.</li>
     *   <li>{@code mergeForwardTokens} — minimum size at which a section's
     *       accumulated text is eligible to emit. Below this threshold the
     *       buffer is held and merges into the next section's content. This
     *       is the effective "floor": raising it coalesces small sibling
     *       sections into larger chunks without changing the max ceiling.</li>
     * </ul>
     *
     * <p>Earlier releases also exposed {@code targetTokens} and {@code
     * minTokens}. Those knobs were never wired to any behaviour in this class;
     * they have been removed to stop advertising levers that do nothing.</p>
     */
    public record Config(int maxTokens, int mergeForwardTokens) {}

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

        // State threaded across heading boundaries so merge-forward can preserve
        // the first section's heading_path when a short chunk rolls into the next.
        Deque<String> headingStack = new ArrayDeque<>();
        State state = new State();
        int[] chunkIndex = {0};

        for (Node child : root.getChildren()) {
            if (child instanceof Heading heading) {
                // Flush any accumulated blocks under the *current* heading path
                // before we shift into the new heading.
                flushBlocks(pageName, chunkIndex, currentHeadingPath(headingStack), state, out);
                adjustHeadingStack(headingStack, heading.getLevel(),
                                   extractHeadingTitle(heading));
            } else {
                state.blocks.add(child);
            }
        }
        flushBlocks(pageName, chunkIndex, currentHeadingPath(headingStack), state, out);
        // Final flush: emit any residual merge-forward buffer even if below threshold,
        // so tail content is never dropped.
        if (!state.pending.isEmpty()) {
            String text = state.pending.toString().strip();
            if (!text.isEmpty()) {
                List<String> hp = state.pendingHeadingPath != null
                    ? state.pendingHeadingPath
                    : currentHeadingPath(headingStack);
                out.add(buildChunk(pageName, chunkIndex[0]++, hp, text));
            }
            state.pending.setLength(0);
            state.pendingHeadingPath = null;
        }
        return out;
    }

    /**
     * Builds a heading path snapshot from the current stack, stripping leading
     * empty entries that the stack pads in when a subheading appears without a
     * parent heading (e.g. a {@code ##} at the top of a page). Downstream
     * callers treat {@code heading_path} as the *meaningful* hierarchy, so a
     * leading {@code ""} placeholder isn't useful to preserve.
     */
    private List<String> currentHeadingPath(Deque<String> stack) {
        List<String> list = new ArrayList<>(stack);
        int firstNonEmpty = 0;
        while (firstNonEmpty < list.size() && list.get(firstNonEmpty).isEmpty()) {
            firstNonEmpty++;
        }
        return List.copyOf(list.subList(firstNonEmpty, list.size()));
    }

    /**
     * Extracts the heading's full title by walking its inline children and
     * concatenating their source ranges. Flexmark's {@code Heading.getText()}
     * returns a {@link com.vladsch.flexmark.util.sequence.BasedSequence} covering
     * the ATX text span in the current version (0.64.8), but that behaviour is
     * brittle across versions: sibling releases have returned only the first
     * child's characters, silently truncating headings that contain inline
     * markup like {@code ## First *emphasized* Section}. Walking the children
     * explicitly and using {@code getChars()} on each one preserves the full
     * source-range title (markers included) regardless of Flexmark version.
     */
    private static String extractHeadingTitle(Heading heading) {
        StringBuilder sb = new StringBuilder();
        Node child = heading.getFirstChild();
        while (child != null) {
            sb.append(child.getChars());
            child = child.getNext();
        }
        return sb.toString().strip();
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

    /**
     * Mutable accumulation state threaded across heading-boundary flushes.
     * {@code blocks} holds AST nodes waiting to be turned into chunks within the
     * current heading scope; {@code pending} holds already-rendered text whose
     * size was below {@code mergeForwardTokens} and is awaiting merge-forward
     * into the next flush. {@code pendingHeadingPath} is the heading_path that
     * was active when the merge-forward buffer first captured content; it
     * "wins" for the merged chunk.
     */
    @SuppressWarnings( "PMD.AvoidStringBufferField" ) // State is constructed per chunking pass and discarded; no long-lived owner.
    private static final class State {
        final List<Node> blocks = new ArrayList<>();
        final StringBuilder pending = new StringBuilder();
        List<String> pendingHeadingPath;
    }

    private int estimateTokens(String s) {
        return (int) Math.ceil(s.length() / 4.0);
    }

    private boolean isAtomic(Node block) {
        if (block instanceof FencedCodeBlock) {
            return true;
        }
        if (block instanceof BulletList || block instanceof OrderedList) {
            // Keep lists intact when they're within a reasonable budget. Splitting
            // a bulleted API reference or ACL table mid-list fragments context that
            // the items only make sense as a group. If a list is pathologically
            // large (> maxTokens*4), fall through so the non-atomic path can split
            // it on item-terminating sentence boundaries rather than emit one
            // chunk larger than the embedder's window.
            return estimateTokens(block.getChars().toString()) <= config.maxTokens() * 4;
        }
        try {
            Class<?> tableBlock = Class.forName("com.vladsch.flexmark.ext.tables.TableBlock");
            if (tableBlock.isInstance(block)) {
                return true;
            }
        } catch (ClassNotFoundException ignored) {
            // Tables extension not on classpath — fine, we'll fall through the budget path.
        }
        return false;
    }

    private void flushBlocks(String pageName, int[] idx, List<String> headingPath,
                             State state, List<Chunk> out) {
        if (state.blocks.isEmpty()) {
            return;
        }

        for (Node block : state.blocks) {
            String blockText = block.getChars().toString();
            int blockTokens = estimateTokens(blockText);

            if (isAtomic(block)) {
                emitPending(pageName, idx, headingPath, state, out);
                if (blockTokens > config.maxTokens() * 2) {
                    LOG.debug("Atomic block exceeds 2x max ({} tokens) on page {}",
                              blockTokens, pageName);
                }
                out.add(buildChunk(pageName, idx[0]++, headingPath, blockText.strip()));
                continue;
            }

            if (blockTokens > config.maxTokens()) {
                emitPending(pageName, idx, headingPath, state, out);
                for (String sentenceChunk : splitOnSentences(blockText, config.maxTokens())) {
                    out.add(buildChunk(pageName, idx[0]++, headingPath, sentenceChunk.strip()));
                }
                continue;
            }

            int pendingTokens = estimateTokens(state.pending.toString());
            if (pendingTokens + blockTokens > config.maxTokens()) {
                emitPending(pageName, idx, headingPath, state, out);
            }
            if (state.pending.length() == 0) {
                // First content landing in this pending buffer owns the heading_path.
                // Defensive copy: makes the contract local even if a future caller
                // forgets to pass an immutable list.
                state.pendingHeadingPath = List.copyOf(headingPath);
            }
            state.pending.append(blockText).append("\n\n");
        }
        state.blocks.clear();
        // At end of a section flush, attempt to emit whatever's pending. If
        // it's below mergeForwardTokens it stays in the buffer for merge-forward.
        emitPending(pageName, idx, headingPath, state, out);
    }

    private void emitPending(String pageName, int[] idx, List<String> fallbackHeadingPath,
                             State state, List<Chunk> out) {
        String text = state.pending.toString().strip();
        if (text.isEmpty()) {
            state.pending.setLength(0);
            state.pendingHeadingPath = null;
            return;
        }
        if (estimateTokens(text) < config.mergeForwardTokens()) {
            // Hold onto it — next block's content merges with it. Leave
            // pendingHeadingPath as set so the first section's path is carried.
            return;
        }
        List<String> hp = state.pendingHeadingPath != null
            ? state.pendingHeadingPath
            : fallbackHeadingPath;
        out.add(buildChunk(pageName, idx[0]++, hp, text));
        state.pending.setLength(0);
        state.pendingHeadingPath = null;
    }

    private List<String> splitOnSentences(String text, int maxTokens) {
        List<String> chunks = new ArrayList<>();
        String[] sentences = text.split("(?<=[.!?])\\s+(?=[A-Z]|\\n)");
        StringBuilder cur = new StringBuilder();
        for (String s : sentences) {
            if (estimateTokens(cur.toString()) + estimateTokens(s) > maxTokens
                && cur.length() > 0) {
                chunks.add(cur.toString());
                cur.setLength(0);
            }
            if (estimateTokens(s) > maxTokens) {
                for (String token : s.split("\\s+")) {
                    if (estimateTokens(cur.toString()) + estimateTokens(token) > maxTokens
                        && cur.length() > 0) {
                        chunks.add(cur.toString());
                        cur.setLength(0);
                        LOG.warn("Single sentence exceeded max tokens ({}); falling back to whitespace split", maxTokens);
                    }
                    cur.append(token).append(' ');
                }
            } else {
                cur.append(s).append(' ');
            }
        }
        if (cur.length() > 0) {
            chunks.add(cur.toString());
        }
        return chunks;
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
