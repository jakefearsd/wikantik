package com.wikantik.knowledge.extraction;

import com.wikantik.api.knowledge.ExtractionContext;
import com.wikantik.api.knowledge.KgNode;
import com.wikantik.api.knowledge.Page;

import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Builds the system + user prompts for the per-page extractor. The system
 * prompt is a constant {@code String} so prompt caching has a chance of
 * hitting (Claude judge later; gemma's effect is smaller but harmless).
 */
public final class PageExtractionPromptBuilder {

    private PageExtractionPromptBuilder() {}

    public static final String SYSTEM_PROMPT =
          "You extract a small, high-quality set of named entities and relations from a single "
        + "wiki page. Output STRICT JSON only, no prose, no markdown fence:\n\n"
        + "{\n"
        + "  \"entities\": [\n"
        + "    { \"name\": str, \"type\": Person|Organization|Place|Event|Product|Technology|Concept,\n"
        + "      \"evidence_span\": str, \"confidence\": 0..1 }   // max 12\n"
        + "  ],\n"
        + "  \"relations\": [\n"
        + "    { \"source\": str, \"target\": str, \"predicate\": ENUM,\n"
        + "      \"evidence_span\": str, \"confidence\": 0..1 }   // max 8\n"
        + "  ]\n"
        + "}\n\n"
        + "Hard rules:\n"
        + "- evidence_span MUST be a verbatim <=200-char quote from the page below. No paraphrase.\n"
        + "- Both source and target of every relation MUST appear in entities[].\n"
        + "- name MUST be a proper-noun, Title-Case canonical form. NEVER emit type-labels\n"
        + "  (\"Concept\", \"Agent\", \"Process\", \"System\", \"User\", \"Software\", \"Data\") as a name.\n"
        + "- predicate MUST be EXACTLY one of the closed vocabulary below — NEVER invent a new\n"
        + "  predicate, vary casing/separators, or emit a free-form phrase. If no listed predicate\n"
        + "  captures the relation cleanly, OMIT the relation. The DB will reject non-vocabulary\n"
        + "  predicates and the proposal will be discarded.\n"
        + "  Closed vocabulary (source → target):\n"
        + "    related_to        — generic association; use only when no more specific fits\n"
        + "    part_of           — A is a part/component of B\n"
        + "    contains          — A contains/includes B\n"
        + "    is_a              — A is a subtype/kind of B\n"
        + "    instance_of       — A is a concrete example/instance of B\n"
        + "    requires          — A requires/depends on B\n"
        + "    enables           — A enables/allows/supports B\n"
        + "    uses              — A uses/invokes/operates on B\n"
        + "    produces          — A produces/emits/generates B\n"
        + "    replaces          — A replaces/supersedes B\n"
        + "    precedes          — A precedes B in time/sequence\n"
        + "    extends           — A extends/builds on B (specialization)\n"
        + "    implements        — A is a concrete implementation of B\n"
        + "    alternative_to    — A is a substitute for B (peer alternatives)\n"
        + "    contrasts_with    — A and B are explicitly differentiated/compared\n"
        + "    compatible_with   — A interoperates with B\n"
        + "    mitigates         — A reduces the harm/risk of B\n"
        + "    defines           — A defines/specifies/describes B\n"
        + "    applies_to        — A is relevant within the scope of B\n"
        + "    located_in        — A is spatially within B\n"
        + "- Prefer Known Entities (below) verbatim. Only propose a brand-new entity if it is\n"
        + "  clearly named, distinct, and not in the Known list.\n"
        + "- If the page genuinely has no proper-noun entities, return empty arrays. That is correct.\n"
        + "- Reasoning is implicit in evidence_span. No \"reasoning\" field.";

    public static String buildUserPrompt(Page page, ExtractionContext ctx) {
        StringBuilder sb = new StringBuilder(2048);
        sb.append("Page: ").append(page.name()).append('\n');
        if (!page.headings().isEmpty()) {
            sb.append("Section path: ").append(String.join(" › ", page.headings())).append('\n');
        }
        String dict = formatDictionary(ctx);
        if (!dict.isEmpty()) {
            sb.append("\nKnown Entities (name :: type) — reuse these names when the page refers to them:\n")
              .append(dict).append('\n');
        }
        sb.append("\nPage body:\n---\n").append(page.body()).append("\n---\n\nReturn ONLY the JSON object.");
        return sb.toString();
    }

    private static String formatDictionary(ExtractionContext ctx) {
        if (ctx == null || ctx.existingNodes() == null || ctx.existingNodes().isEmpty()) {
            return "";
        }
        return ctx.existingNodes().stream()
            .sorted((a, b) -> a.name().compareToIgnoreCase(b.name()))
            .map(PageExtractionPromptBuilder::formatNode)
            .collect(Collectors.joining("\n"));
    }

    private static String formatNode(KgNode n) {
        String type = n.nodeType() == null ? "Concept" : n.nodeType();
        return "- " + n.name() + " :: " + type.toLowerCase(Locale.ROOT);
    }
}
