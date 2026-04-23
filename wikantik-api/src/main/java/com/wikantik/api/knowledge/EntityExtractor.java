package com.wikantik.api.knowledge;

/**
 * Pluggable contract for deriving entities, relations and mentions from a
 * saved content chunk. Implementations run out-of-band from the save path
 * (see {@code AsyncEntityExtractionListener}), so they may block on network
 * calls to an LLM backend; the listener owns executor and timeout policy.
 *
 * <p>Implementations MUST NOT write to {@code kg_nodes} or {@code kg_edges}
 * directly — all node/edge changes route through the {@code kg_proposals}
 * workflow. They MAY return mentions keyed by existing node ids, but the
 * canonical resolution (exact-name match → id) is done by the caller so
 * extractors stay stateless.
 *
 * <p>Errors are returned as an empty {@link ExtractionResult} with a
 * non-zero latency; the extractor logs detail internally. The listener is
 * responsible for translating poisoned chunks into a skip, not a retry.
 */
public interface EntityExtractor {

    /**
     * Short, stable identifier for this extractor (e.g. {@code "claude"},
     * {@code "ollama"}). Written to {@code chunk_entity_mentions.extractor}
     * and to the extractor metrics tag.
     */
    String code();

    /**
     * Run extraction over a single chunk. Must never throw — poisoned input
     * or backend failures are translated to {@link ExtractionResult#empty}.
     */
    ExtractionResult extract( ExtractionChunk chunk, ExtractionContext context );
}
