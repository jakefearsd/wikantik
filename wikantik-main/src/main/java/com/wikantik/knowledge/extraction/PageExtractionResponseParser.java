package com.wikantik.knowledge.extraction;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wikantik.api.knowledge.ExtractedEntity;
import com.wikantik.api.knowledge.ExtractedRelation;
import com.wikantik.api.knowledge.PageExtractionResult;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Parses the raw JSON returned by an LLM page extractor into a
 * grounded, capped {@link PageExtractionResult}. Schema-violators are
 * silently dropped (parser is fail-open: we'd rather ship N-1 entities
 * than 0 because of one malformed item).
 */
public final class PageExtractionResponseParser {

    private static final Logger LOG = LogManager.getLogger(PageExtractionResponseParser.class);

    /** Names the model is forbidden to emit. Lower-cased on comparison. */
    private static final Set<String> BANNED_NAMES = Set.of(
        "concept", "agent", "process", "system", "user", "software", "data"
    );

    /** Closed enum of allowed types. Lower-cased on comparison. */
    private static final Set<String> ALLOWED_TYPES = Set.of(
        "person", "organization", "place", "event", "product", "technology", "concept"
    );

    private final EvidenceGroundingVerifier verifier;
    private final int maxEntities;
    private final int maxRelations;

    public PageExtractionResponseParser(EvidenceGroundingVerifier verifier,
                                        int maxEntities, int maxRelations) {
        this.verifier = verifier;
        this.maxEntities = maxEntities;
        this.maxRelations = maxRelations;
    }

    public PageExtractionResult parse(String json, String extractorCode, String pageName,
                                       String pageBody, Duration latency) {
        int rawE = 0, rawR = 0, rejectedUngrounded = 0, rejectedBannedName = 0;
        List<ExtractedEntity> entities = new ArrayList<>();
        List<ExtractedRelation> relations = new ArrayList<>();

        JsonObject root;
        try {
            JsonElement el = JsonParser.parseString(json);
            if (!el.isJsonObject()) {
                return PageExtractionResult.empty(extractorCode, pageName, latency);
            }
            root = el.getAsJsonObject();
        } catch (RuntimeException e) {
            LOG.warn("PageExtractionResponseParser: malformed JSON for page '{}': {}",
                     pageName, e.getMessage());
            return PageExtractionResult.empty(extractorCode, pageName, latency);
        }

        JsonArray entityArr = arrayOrEmpty(root, "entities");
        rawE = entityArr.size();
        for (JsonElement e : entityArr) {
            if (!e.isJsonObject()) continue;
            JsonObject obj = e.getAsJsonObject();
            String name = stringOrNull(obj, "name");
            String type = stringOrNull(obj, "type");
            String span = stringOrNull(obj, "evidence_span");
            Double conf = doubleOrDefault(obj, "confidence", 0.5);
            if (name == null || type == null || span == null) continue;

            if (BANNED_NAMES.contains(name.trim().toLowerCase(Locale.ROOT))) {
                rejectedBannedName++;
                continue;
            }
            if (!ALLOWED_TYPES.contains(type.trim().toLowerCase(Locale.ROOT))) {
                continue;
            }
            EvidenceGroundingVerifier.Decision d = verifier.evaluate(span, pageBody);
            if (!d.grounded()) {
                rejectedUngrounded++;
                continue;
            }
            entities.add(new ExtractedEntity(name.trim(), titleCaseType(type), span, clampConf(conf)));
        }

        if (entities.size() > maxEntities) {
            entities.sort(Comparator.comparingDouble(ExtractedEntity::confidence).reversed());
            entities = new ArrayList<>(entities.subList(0, maxEntities));
        }

        Set<String> entityNames = new HashSet<>();
        for (ExtractedEntity e : entities) entityNames.add(e.name().toLowerCase(Locale.ROOT));

        JsonArray relArr = arrayOrEmpty(root, "relations");
        rawR = relArr.size();
        for (JsonElement r : relArr) {
            if (!r.isJsonObject()) continue;
            JsonObject obj = r.getAsJsonObject();
            String src  = stringOrNull(obj, "source");
            String tgt  = stringOrNull(obj, "target");
            String pred = stringOrNull(obj, "predicate");
            String span = stringOrNull(obj, "evidence_span");
            Double conf = doubleOrDefault(obj, "confidence", 0.5);
            if (src == null || tgt == null || pred == null || span == null) continue;

            if (!entityNames.contains(src.trim().toLowerCase(Locale.ROOT))
             || !entityNames.contains(tgt.trim().toLowerCase(Locale.ROOT))) {
                continue;
            }
            EvidenceGroundingVerifier.Decision d = verifier.evaluate(span, pageBody);
            if (!d.grounded()) {
                rejectedUngrounded++;
                continue;
            }
            relations.add(new ExtractedRelation(src.trim(), tgt.trim(), pred.trim(), span, clampConf(conf)));
        }

        if (relations.size() > maxRelations) {
            relations.sort(Comparator.comparingDouble(ExtractedRelation::confidence).reversed());
            relations = new ArrayList<>(relations.subList(0, maxRelations));
        }

        return new PageExtractionResult(extractorCode, pageName, entities, relations,
            new PageExtractionResult.Stats(rawE, rawR, rejectedUngrounded, rejectedBannedName, latency));
    }

    private static JsonArray arrayOrEmpty(JsonObject root, String key) {
        JsonElement el = root.get(key);
        return el != null && el.isJsonArray() ? el.getAsJsonArray() : new JsonArray();
    }

    private static String stringOrNull(JsonObject o, String key) {
        JsonElement el = o.get(key);
        return el == null || el.isJsonNull() || !el.isJsonPrimitive() ? null : el.getAsString();
    }

    private static double doubleOrDefault(JsonObject o, String key, double def) {
        JsonElement el = o.get(key);
        if (el == null || el.isJsonNull() || !el.isJsonPrimitive()) return def;
        try {
            return el.getAsDouble();
        } catch (RuntimeException ex) {
            return def;
        }
    }

    private static double clampConf(double v) {
        if (Double.isNaN(v) || v < 0.0) return 0.0;
        if (v > 1.0) return 1.0;
        return v;
    }

    private static String titleCaseType(String t) {
        String trimmed = t.trim();
        if (trimmed.isEmpty()) return trimmed;
        return Character.toUpperCase(trimmed.charAt(0)) + trimmed.substring(1).toLowerCase(Locale.ROOT);
    }
}
