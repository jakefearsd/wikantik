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

    /** Closed enum of allowed types — the canonical 9-class entity vocabulary. Lower-cased on comparison. */
    private static final Set<String> ALLOWED_TYPES =
        com.wikantik.api.knowledge.EntityTypeVocabulary.ENTITY_CLASS_SET;

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
        final JsonObject root = parseRoot(json, pageName);
        if (root == null) {
            return PageExtractionResult.empty(extractorCode, pageName, latency);
        }

        final RejectCounts counts = new RejectCounts();

        final JsonArray entityArr = arrayOrEmpty(root, "entities");
        final List<ExtractedEntity> entities = parseEntities(entityArr, pageBody, counts);

        final Set<String> entityNames = new HashSet<>();
        for (ExtractedEntity e : entities) entityNames.add(e.name().toLowerCase(Locale.ROOT));

        final JsonArray relArr = arrayOrEmpty(root, "relations");
        final List<ExtractedRelation> relations = parseRelations(relArr, pageBody, entityNames, counts);

        return new PageExtractionResult(extractorCode, pageName, entities, relations,
            new PageExtractionResult.Stats(entityArr.size(), relArr.size(),
                counts.ungrounded, counts.bannedName, latency));
    }

    /** Parses the raw response into a JSON object, or null if it is non-object/malformed. */
    private JsonObject parseRoot(String json, String pageName) {
        try {
            JsonElement el = JsonParser.parseString(json);
            if (!el.isJsonObject()) {
                return null;
            }
            return el.getAsJsonObject();
        } catch (RuntimeException e) {
            LOG.warn("PageExtractionResponseParser: malformed JSON for page '{}': {}",
                     pageName, e.getMessage());
            return null;
        }
    }

    /**
     * Reads the {@code entities} array into grounded, allowed-type, non-banned entities (capped to
     * {@code maxEntities} by confidence). Rejected-entity reasons accumulate into {@code counts}.
     */
    private List<ExtractedEntity> parseEntities(JsonArray entityArr, String pageBody, RejectCounts counts) {
        List<ExtractedEntity> entities = new ArrayList<>();
        for (JsonElement e : entityArr) {
            if (!e.isJsonObject()) continue;
            JsonObject obj = e.getAsJsonObject();
            String name = stringOrNull(obj, "name");
            String type = stringOrNull(obj, "type");
            String span = stringOrNull(obj, "evidence_span");
            Double conf = doubleOrDefault(obj, "confidence", 0.5);
            if (name == null || type == null || span == null) continue;

            if (BANNED_NAMES.contains(name.trim().toLowerCase(Locale.ROOT))) {
                counts.bannedName++;
                continue;
            }
            if (!ALLOWED_TYPES.contains(type.trim().toLowerCase(Locale.ROOT))) {
                continue;
            }
            EvidenceGroundingVerifier.Decision d = verifier.evaluate(span, pageBody);
            if (!d.grounded()) {
                counts.ungrounded++;
                continue;
            }
            entities.add(new ExtractedEntity(name.trim(), titleCaseType(type), span, clampConf(conf)));
        }
        if (entities.size() > maxEntities) {
            entities.sort(Comparator.comparingDouble(ExtractedEntity::confidence).reversed());
            entities = new ArrayList<>(entities.subList(0, maxEntities));
        }
        return entities;
    }

    /**
     * Reads the {@code relations} array into grounded relations whose endpoints are both in
     * {@code entityNames} (capped to {@code maxRelations} by confidence). Ungrounded rejections
     * accumulate into {@code counts}.
     */
    private List<ExtractedRelation> parseRelations(JsonArray relArr, String pageBody,
                                                   Set<String> entityNames, RejectCounts counts) {
        List<ExtractedRelation> relations = new ArrayList<>();
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
                counts.ungrounded++;
                continue;
            }
            relations.add(new ExtractedRelation(src.trim(), tgt.trim(), pred.trim(), span, clampConf(conf)));
        }
        if (relations.size() > maxRelations) {
            relations.sort(Comparator.comparingDouble(ExtractedRelation::confidence).reversed());
            relations = new ArrayList<>(relations.subList(0, maxRelations));
        }
        return relations;
    }

    /** Mutable accumulator for the per-parse rejection counters threaded through both loops. */
    private static final class RejectCounts {
        private int ungrounded;
        private int bannedName;
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
