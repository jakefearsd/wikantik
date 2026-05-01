package com.wikantik.knowledge.extraction;

import com.wikantik.api.knowledge.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Pure function: groups extraction results by canonical signature, picks
 * the most-frequent original-cased display name by vote, aggregates support
 * evidence, and emits one ConsolidatedProposal per logical claim.
 */
public final class ProposalConsolidator {

    public List<ConsolidatedProposal> consolidate(Stream<PageExtractionResult> pageResults) {
        Map<String, NodeBuilder> nodes = new LinkedHashMap<>();
        Map<String, EdgeBuilder> edges = new LinkedHashMap<>();

        pageResults.forEach(pr -> {
            for (ExtractedEntity e : pr.entities()) {
                NodeSignature sig = NodeSignature.of(e.name(), e.type());
                nodes.computeIfAbsent(sig.asHash(),
                    k -> new NodeBuilder(sig.asHash(), e.name(), titleCase(e.type())))
                     .addSupport(pr.pageName(), e.evidenceSpan(), e.confidence(),
                                 pr.extractorCode(), e.name());
            }
            for (ExtractedRelation r : pr.relations()) {
                EdgeSignature sig = EdgeSignature.of(r.source(), r.target(), r.predicate());
                edges.computeIfAbsent(sig.asHash(),
                    k -> new EdgeBuilder(sig.asHash(), r.source(), r.target(),
                                         EdgeSignature.normalizePredicate(r.predicate())))
                     .addSupport(pr.pageName(), r.evidenceSpan(), r.confidence(), pr.extractorCode());
            }
        });

        List<ConsolidatedProposal> out = new ArrayList<>(nodes.size() + edges.size());
        for (NodeBuilder b : nodes.values()) out.add(b.build());
        for (EdgeBuilder b : edges.values()) out.add(b.build());
        return out;
    }

    private static String titleCase(String t) {
        String s = t.trim();
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase(java.util.Locale.ROOT);
    }

    /** Package-private mutable accumulator for nodes. */
    static final class NodeBuilder {
        private final String signature;
        private final String firstSeenName;
        private final String type;
        private final List<SupportEvidence> support = new ArrayList<>();
        private final Map<String, Integer> nameVotes = new HashMap<>();
        private double confidenceSum = 0.0;

        NodeBuilder(String signature, String firstSeenName, String type) {
            this.signature = signature;
            this.firstSeenName = firstSeenName;
            this.type = type;
        }

        void addSupport(String page, String span, double conf, String extractorCode, String emittedName) {
            support.add(new SupportEvidence(page, span, conf, extractorCode));
            confidenceSum += conf;
            nameVotes.merge(emittedName, 1, Integer::sum);
        }

        ConsolidatedProposal build() {
            String winner = firstSeenName;
            int best = nameVotes.getOrDefault(firstSeenName, 0);
            for (var e : nameVotes.entrySet()) {
                if (e.getValue() > best) {
                    winner = e.getKey();
                    best = e.getValue();
                }
            }
            double agg = support.isEmpty() ? 0.0 : confidenceSum / support.size();
            return ConsolidatedProposal.newNode(signature, winner, type, support, agg);
        }
    }

    /** Package-private mutable accumulator for edges. */
    static final class EdgeBuilder {
        private final String signature;
        private final String source;
        private final String target;
        private final String predicate;
        private final List<SupportEvidence> support = new ArrayList<>();
        private double confidenceSum = 0.0;

        EdgeBuilder(String signature, String source, String target, String predicate) {
            this.signature = signature;
            this.source = source;
            this.target = target;
            this.predicate = predicate;
        }

        void addSupport(String page, String span, double conf, String extractorCode) {
            support.add(new SupportEvidence(page, span, conf, extractorCode));
            confidenceSum += conf;
        }

        ConsolidatedProposal build() {
            double agg = support.isEmpty() ? 0.0 : confidenceSum / support.size();
            return ConsolidatedProposal.newEdge(signature, source, target, predicate, support, agg);
        }
    }
}
