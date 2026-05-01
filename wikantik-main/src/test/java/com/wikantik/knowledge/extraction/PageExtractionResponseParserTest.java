package com.wikantik.knowledge.extraction;

import com.wikantik.api.knowledge.PageExtractionResult;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

class PageExtractionResponseParserTest {

    private final PageExtractionResponseParser parser = new PageExtractionResponseParser(
        new EvidenceGroundingVerifier(), 12, 8);

    @Test
    void parsesValidJson() {
        String body = "Python created by Guido in 1991.";
        String json = "{\"entities\":["
                + "{\"name\":\"Python\",\"type\":\"Technology\",\"evidence_span\":\"Python created by Guido\",\"confidence\":0.9},"
                + "{\"name\":\"Guido\",\"type\":\"Person\",\"evidence_span\":\"created by Guido\",\"confidence\":0.85}"
                + "],\"relations\":["
                + "{\"source\":\"Python\",\"target\":\"Guido\",\"predicate\":\"created_by\",\"evidence_span\":\"Python created by Guido\",\"confidence\":0.9}"
                + "]}";
        PageExtractionResult r = parser.parse(json, "ollama:gemma4", "PythonPage", body, Duration.ofMillis(100));
        assertEquals(2, r.entities().size());
        assertEquals(1, r.relations().size());
        assertEquals(0, r.stats().rejectedUngrounded());
        assertEquals(0, r.stats().rejectedBannedName());
    }

    @Test
    void dropsBannedNameEntities() {
        String body = "Concept is everywhere. The system is humming.";
        String json = "{\"entities\":["
                + "{\"name\":\"Concept\",\"type\":\"Concept\",\"evidence_span\":\"Concept is everywhere.\",\"confidence\":1.0},"
                + "{\"name\":\"Python\",\"type\":\"Technology\",\"evidence_span\":\"system is humming\",\"confidence\":0.5}"
                + "],\"relations\":[]}";
        PageExtractionResult r = parser.parse(json, "x", "P", body, Duration.ZERO);
        assertEquals(1, r.entities().size());
        assertEquals("Python", r.entities().get(0).name());
        assertEquals(1, r.stats().rejectedBannedName());
    }

    @Test
    void dropsUngroundedEntities() {
        String body = "Real text only.";
        String json = "{\"entities\":["
                + "{\"name\":\"Python\",\"type\":\"Technology\",\"evidence_span\":\"NOT IN BODY\",\"confidence\":1.0}"
                + "],\"relations\":[]}";
        PageExtractionResult r = parser.parse(json, "x", "P", body, Duration.ZERO);
        assertEquals(0, r.entities().size());
        assertEquals(1, r.stats().rejectedUngrounded());
    }

    @Test
    void dropsRelationsWithUnknownEndpoints() {
        String body = "Python and Guido.";
        String json = "{\"entities\":["
                + "{\"name\":\"Python\",\"type\":\"Technology\",\"evidence_span\":\"Python\",\"confidence\":0.9}"
                + "],\"relations\":["
                + "{\"source\":\"Python\",\"target\":\"Ruby\",\"predicate\":\"alt\",\"evidence_span\":\"Python\",\"confidence\":0.9}"
                + "]}";
        PageExtractionResult r = parser.parse(json, "x", "P", body, Duration.ZERO);
        assertEquals(0, r.relations().size());
    }

    @Test
    void enforcesEntityCap() {
        StringBuilder sb = new StringBuilder("{\"entities\":[");
        for (int i = 0; i < 14; i++) {
            if (i > 0) sb.append(',');
            double conf = (i + 1) / 100.0;
            sb.append("{\"name\":\"E").append(i)
              .append("\",\"type\":\"Concept\",\"evidence_span\":\"x\",\"confidence\":").append(conf).append("}");
        }
        sb.append("],\"relations\":[]}");
        PageExtractionResult r = parser.parse(sb.toString(), "x", "P", "x", Duration.ZERO);
        assertEquals(12, r.entities().size());
        assertTrue(r.entities().stream().noneMatch(e -> e.name().equals("E0")));
    }

    @Test
    void malformedJsonReturnsEmptyResult() {
        PageExtractionResult r = parser.parse("not json", "x", "P", "body", Duration.ZERO);
        assertEquals(0, r.entities().size());
        assertEquals(0, r.relations().size());
    }

    @Test
    void emptyArraysAreValid() {
        PageExtractionResult r = parser.parse("{\"entities\":[],\"relations\":[]}",
            "x", "P", "body", Duration.ZERO);
        assertEquals(0, r.entities().size());
        assertEquals(0, r.relations().size());
    }
}
