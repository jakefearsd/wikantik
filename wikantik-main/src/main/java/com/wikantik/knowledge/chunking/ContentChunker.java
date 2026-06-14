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
     * Flexmark's tables extension is optional — cache the probe so we don't
     * attempt the classpath lookup (and log about it) on every block.
     */
    private static final Class<?> TABLE_BLOCK_CLASS = loadTableBlockClass();

    private static Class<?> loadTableBlockClass() {
        try {
            return Class.forName("com.vladsch.flexmark.ext.tables.TableBlock");
        } catch (ClassNotFoundException e) {
            LOG.info("Flexmark tables extension not on classpath — table blocks will "
                    + "fall through the non-atomic budget path: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Chunker tuning. Only two knobs actually change behaviour:
     * <ul>
     *   <li>{@code maxTokens} — hard upper bound on a non-atomic chunk's size.
     *       When appending a block would exceed this, the current buffer is
     *       emitted first. Oversized atomic blocks (code/tables/lists within
     *       budget) pass through intact; oversized paragraphs fall back to
     *       sentence-level splitting.</li>
     *   <li>{@code mergeForwardTokens} — size below which a section's accumulated
     *       text is held inside a flush rather than emitted immediately. Keeps a
     *       section's own short blocks together until the section ends.</li>
     *   <li>{@code fragmentFloorTokens} — the cross-section floor. A section whose
     *       whole text is below this is treated as a fragment: instead of becoming
     *       its own (tiny, noisy) chunk it merges forward into the NEXT section and
     *       adopts that section's heading_path, so the dominant section stays
     *       findable and we don't emit sub-floor fragments. A section at/above the
     *       floor always stands alone under its own heading (heading fidelity).
     *       Must be {@code <= mergeForwardTokens}.</li>
     *   <li>{@code overlapTokens} — sliding-window overlap. After chunking, each
     *       chunk's tail (this many tokens) is prepended to the next chunk under the
     *       same heading_path, so a boundary-straddling fact appears whole in one
     *       chunk. {@code 0} disables overlap (the historical behaviour).</li>
     * </ul>
     *
     * <p>Earlier releases also exposed {@code targetTokens} and {@code
     * minTokens}. Those knobs were never wired to any behaviour in this class;
     * they have been removed to stop advertising levers that do nothing.</p>
     */
    public record Config(int maxTokens, int mergeForwardTokens, int fragmentFloorTokens,
                         int overlapTokens) {}

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

        // Within a section, short blocks merge forward into one chunk. Across a
        // heading boundary they MUST NOT — each chunk's heading_path has to match
        // the section its content came from, or the later section is unfindable by
        // its own heading and any citation to it is mis-anchored.
        Deque<String> headingStack = new ArrayDeque<>();
        State state = new State();
        int[] chunkIndex = {0};

        for (Node child : root.getChildren()) {
            if (child instanceof Heading heading) {
                // Flush any accumulated blocks under the *current* heading path
                // before we shift into the new heading.
                flushBlocks(pageName, chunkIndex, currentHeadingPath(headingStack), state, out);
                // Heading fidelity at the boundary: a section at/above the fragment
                // floor is emitted here under its OWN heading_path (so it can't steal
                // the next section's heading — the OllamaSetup/AgentMemory defect). A
                // sub-floor fragment is left held; it merges into the next section and
                // adopts that heading (see flushBlocks), so we never emit a tiny chunk.
                emitSectionAtBoundary(pageName, chunkIndex, state, out);
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
        return applyOverlap(out);
    }

    /**
     * Prepends the tail of each chunk to the next chunk under the SAME heading_path, so a
     * fact that straddles a chunk boundary appears whole in at least one chunk. Each chunk's
     * tail is taken from the ORIGINAL (un-overlapped) text, so overlap does not compound.
     * Off when {@code overlapTokens <= 0}. The 2026-06-14 probe showed a small @5/@20 gain.
     */
    private List<Chunk> applyOverlap(List<Chunk> chunks) {
        final int overlapChars = config.overlapTokens() * 4;
        if (overlapChars <= 0 || chunks.size() < 2) {
            return chunks;
        }
        final List<Chunk> out = new ArrayList<>(chunks.size());
        out.add(chunks.get(0));
        for (int i = 1; i < chunks.size(); i++) {
            final Chunk prev = chunks.get(i - 1);
            final Chunk cur = chunks.get(i);
            if (!prev.headingPath().equals(cur.headingPath())) {
                out.add(cur);
                continue;
            }
            final String pt = prev.text();
            final String tail = pt.length() <= overlapChars ? pt : pt.substring(pt.length() - overlapChars);
            out.add(buildChunk(cur.pageName(), cur.chunkIndex(), cur.headingPath(),
                               tail + "\n\n" + cur.text()));
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
     * into the next block. {@code pendingHeadingPath} is the heading_path of the
     * section whose content is in the buffer. The buffer is force-emitted at every
     * heading boundary, so it only ever holds content from a single section — its
     * heading_path is always correct, never inherited from an earlier section.
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
        return TABLE_BLOCK_CLASS != null && TABLE_BLOCK_CLASS.isInstance(block);
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
            } else if (!headingPath.equals(state.pendingHeadingPath)) {
                // A sub-floor fragment from a previous section is merging into THIS
                // section (real sections were already emitted at the boundary). Adopt
                // this destination heading so the dominant section stays findable.
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
            // Below threshold — hold for merge-forward with the next block IN THE SAME
            // section. A heading boundary force-emits it first (see the main loop), so it
            // never merges across sections.
            return;
        }
        List<String> hp = state.pendingHeadingPath != null
            ? state.pendingHeadingPath
            : fallbackHeadingPath;
        out.add(buildChunk(pageName, idx[0]++, hp, text));
        state.pending.setLength(0);
        state.pendingHeadingPath = null;
    }

    /**
     * Called at each heading boundary. If the held buffer is at/above the fragment
     * floor it is emitted under its own {@code pendingHeadingPath} (heading fidelity:
     * a real section stands alone and keeps its heading). If it is below the floor it
     * is LEFT held, so it merges into the next section and adopts that section's
     * heading (see {@code flushBlocks}) — no sub-floor fragment chunk is emitted.
     */
    private void emitSectionAtBoundary(String pageName, int[] idx, State state, List<Chunk> out) {
        String text = state.pending.toString().strip();
        if (text.isEmpty()) {
            state.pending.setLength(0);
            state.pendingHeadingPath = null;
            return;
        }
        if (estimateTokens(text) < config.fragmentFloorTokens()) {
            return; // sub-floor fragment — hold for merge-forward into the next section
        }
        List<String> hp = state.pendingHeadingPath != null ? state.pendingHeadingPath : List.of();
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
