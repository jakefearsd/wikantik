package com.wikantik.api.knowledge;

import java.time.Duration;
import java.util.List;

public record PageExtractionResult(String extractorCode,
                                    String pageName,
                                    List<ExtractedEntity> entities,
                                    List<ExtractedRelation> relations,
                                    Stats stats) {

    public PageExtractionResult {
        if (extractorCode == null || extractorCode.isBlank()) {
            throw new IllegalArgumentException("extractorCode must not be blank");
        }
        if (pageName == null || pageName.isBlank()) {
            throw new IllegalArgumentException("pageName must not be blank");
        }
        entities  = entities  == null ? List.of() : List.copyOf(entities);
        relations = relations == null ? List.of() : List.copyOf(relations);
        if (stats == null) {
            throw new IllegalArgumentException("stats must not be null");
        }
    }

    public static PageExtractionResult empty(String extractorCode, String pageName, Duration latency) {
        return new PageExtractionResult(extractorCode, pageName, List.of(), List.of(),
            new Stats(0, 0, 0, 0, latency));
    }

    public record Stats(int rawEntities,
                        int rawRelations,
                        int rejectedUngrounded,
                        int rejectedBannedName,
                        Duration latency) {
        public Stats {
            if (latency == null) {
                throw new IllegalArgumentException("latency must not be null");
            }
        }
    }
}
