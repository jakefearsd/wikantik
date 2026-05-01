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
        + "    { \"source\": str, \"target\": str, \"predicate\": str,\n"
        + "      \"evidence_span\": str, \"confidence\": 0..1 }   // max 8\n"
        + "  ]\n"
        + "}\n\n"
        + "Hard rules:\n"
        + "- evidence_span MUST be a verbatim <=200-char quote from the page below. No paraphrase.\n"
        + "- Both source and target of every relation MUST appear in entities[].\n"
        + "- name MUST be a proper-noun, Title-Case canonical form. NEVER emit type-labels\n"
        + "  (\"Concept\", \"Agent\", \"Process\", \"System\", \"User\", \"Software\", \"Data\") as a name.\n"
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
