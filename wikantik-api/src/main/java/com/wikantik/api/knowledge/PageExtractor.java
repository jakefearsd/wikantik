package com.wikantik.api.knowledge;

/**
 * Per-page entity / relation extractor. Implementations talk to an LLM (Ollama,
 * Claude) and return grounded, capped, schema-conforming results. Extractors
 * never throw on extraction failure — they return an empty result with stats
 * populated. Constructor errors (bad config) are still propagated.
 */
public interface PageExtractor {
    /** Stable identifier carried into chunk_entity_mentions.extractor and metrics. */
    String code();

    PageExtractionResult extract(Page page, ExtractionContext context);
}
