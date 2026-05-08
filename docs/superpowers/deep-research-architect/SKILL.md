---
name: deep-research-architect
description: Standardized workflow for creating highly detailed, thoroughly researched, and data-validated wiki articles. Use when asked for full articles, comprehensive deep-dives, or authoritative technical/financial documentation. Mandates exhaustive detail and cross-disciplinary verification.
---

# Deep Research Architect

The **Deep Research Architect** skill specializes in transforming brief user requests into highly detailed, authoritative, and multi-dimensional wiki articles. It bridges the gap between general AI knowledge and current, high-fidelity research by mandating an exhaustive "Web-First, Evidence-Heavy" workflow.

## Core Procedural Instructions

### 1. The Thorough Research Loop
Never rely solely on internal training data for technical, financial, or historical articles.

*   **Step A: Breadth Search.** Use `google_web_search` with **keyword-driven, natural language queries**.
    *   **CRITICAL ANTI-PATTERN:** Never surround the entire query in quotes (e.g., use `historical asset correlation 1900-2024`, NOT `"historical asset correlation 1900-2024"`). Exact match requirements cause high-fidelity searches to fail.
    *   **Strategy**: Use multiple parallel searches with variations of the topic keywords to capture the widest possible index of sources.
*   **Step B: Multi-Source Depth Fetch.** Use `web_fetch` on **at least 3-4** high-quality URLs to extract specific data points, coefficients, historical dates, and technical nuances.
*   **Step C: Conflict & Nuance Analysis.** Actively look for "Regime Shifts" (e.g., Pre- vs. Post-1971) and "Counter-Arguments" to provide a balanced, authoritative perspective.

### 2. Standards for Exhaustive Detail
Every article must be **substantial in length and depth**:
*   **Multi-Layered Structure**: Use deep heading hierarchies (H1 to H4) to break down complex topics into digestible but exhaustive components.
*   **Quantitative Foundations**: Mandate multiple data tables or matrices (e.g., correlation matrices, performance by epoch, error rates, model coefficients).
*   **Historical Anchors**: Use specific, named historical events (e.g., "The Call Loan Spiral of 1929") to illustrate theoretical concepts in practice.
*   **Case Studies & Worked Examples**: Provide detailed "step-by-step" examples (e.g., a worked Bayesian update or a stress tensor calculation for a specific bridge type).
*   **Real-World Bridging**: Explicit "Real-World Application" sections connecting the theory to practical domains (Software Engineering, Finance, Medicine, Logistics).

### 3. Structural & Mathematical Integrity
*   **LaTeX Authority**: Strictly follow the rules in `GEMINI.md`. Use newline-delimited `$$` for display math and whitespace-free `$inline$` delimiters.
*   **The Structural Spine**: Mandatory YAML frontmatter. Use `relations:` to build a dense knowledge graph.
*   **Atomic Delivery**: Prefer a single, complete `write_file` for new articles to maintain block-delimiter integrity and consistent tone.

## Available Resources
*   `google_web_search`: Primary tool for finding the "State of the Art" and historical data.
*   `web_fetch`: Primary tool for deep-diving into specific research papers or data-heavy reports.
*   `MathematicsHub`: The central directory for all mathematical and statistical techniques.
*   `GEMINI.md`: The authoritative project instruction set.

## When to Activate
Activate this skill whenever the user asks for:
*   "Full articles" or "Highly detailed deep-dives."
*   Financial, Economic, or Mathematical documentation.
*   Historical analysis or Systemic shock research.
*   Any topic requiring current, authoritative, and data-validated statistics.
