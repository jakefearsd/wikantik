---
status: official
cluster: computer-science-foundations
type: article
title: Regular Expressions and Finite Automata
date: '2026-05-04'
summary: How the theoretical foundations of regular expressions and finite automata
  power text processing and link discovery in Wikantik.
canonical_id: 01KQTD79QGKT9YW3THM0HGHA35
verified_at: '2026-05-04T21:10:44.598011331Z'
verified_by: gemini-cli-mcp-client
---
# Regular Expressions and Finite Automata

**Regular Expressions (Regex)** and **Finite Automata** are foundational concepts in computer science used for pattern matching and text processing. In Wikantik, these tools are used extensively for parsing Markdown, scanning links, and validating metadata.

## Finite Automata
A Finite Automaton is a mathematical model of computation. It consists of a finite number of states and transitions between them based on input symbols.
- **Deterministic (DFA):** For every state and input, there is exactly one transition.
- **Non-deterministic (NFA):** Can have multiple transitions for the same input or "epsilon" transitions (without input).

## Regular Expressions
Regex is a formal language used to describe sets of strings. Every regular expression can be converted into an equivalent Finite Automaton (and vice versa).

### Common Syntax
- `.` (Any character)
- `*` (Zero or more)
- `+` (One or more)
- `[a-z]` (Character class)
- `^` / `$` (Anchors)

## Applications in Wikantik

### 1. Markdown Parsing
The **Flexmark** parser used by Wikantik uses complex regular expressions to identify headings, bold text, links, and code blocks within the Markdown source.

### 2. Link Scanning
The `MarkdownLinkScanner` (in `wikantik-api`) uses regex to find internal wiki links (e.g., `[[PageName]]`) and external URLs. 
- **Example:** `\[\[([^|\]]+)(?:\|([^\]]+))?\]\]` matches standard wiki brackets with optional display text.

### 3. Frontmatter Validation
Wikantik uses regex to validate the format of mandatory fields like `canonical_id` (ensuring it is a 26-character ULID) and `date`.

### 4. Search and Retrieval
While the primary search is BM25-based, regex can be used in administrative tools to perform "power searches" across the corpus for specific patterns or legacy JSPWiki constructs.

## See Also
- [Markdown Links](MarkdownLinks) — The syntax powered by these patterns.
- [Frontmatter Conventions](FrontmatterConventions) — How metadata is validated.
- [Search and Retrieval](WikantikSearchAndRetrieval) — The broader context of finding information.
