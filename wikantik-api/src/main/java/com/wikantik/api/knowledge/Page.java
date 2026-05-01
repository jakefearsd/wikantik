package com.wikantik.api.knowledge;

import java.util.List;

/**
 * A page presented to {@link PageExtractor#extract}. {@code pageId} is the
 * canonical_id where available; null for pages without one (the extractor
 * uses {@code name} as a fallback identifier in metrics + logs).
 */
public record Page(String name,
                   String pageId,
                   String body,
                   String summary,
                   List<String> headings) {
    public Page {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (body == null) {
            throw new IllegalArgumentException("body must not be null");
        }
        headings = headings == null ? List.of() : List.copyOf(headings);
    }
}
