package com.wikantik.api.knowledge;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class PageExtractionResultTest {

    @Test
    void emptyResultIsNonNullCollections() {
        PageExtractionResult empty = PageExtractionResult.empty("ollama:gemma4", "Page1", Duration.ofMillis(10));
        assertNotNull(empty.entities());
        assertNotNull(empty.relations());
        assertEquals(0, empty.entities().size());
        assertEquals(0, empty.relations().size());
        assertEquals("Page1", empty.pageName());
    }

    @Test
    void resultExposesStats() {
        PageExtractionResult.Stats stats = new PageExtractionResult.Stats(10, 5, 2, 1, Duration.ofMillis(100));
        PageExtractionResult r = new PageExtractionResult("ollama:gemma4", "Page1",
            List.of(new ExtractedEntity("Python", "Technology", "Python is...", 0.9)),
            List.of(),
            stats);
        assertEquals(10, r.stats().rawEntities());
        assertEquals(2, r.stats().rejectedUngrounded());
    }

    @Test
    void extractedEntityValidates() {
        assertThrows(IllegalArgumentException.class,
            () -> new ExtractedEntity("", "Technology", "x", 0.5));
        assertThrows(IllegalArgumentException.class,
            () -> new ExtractedEntity("X", "Technology", "x", -0.1));
    }
}
