---
cluster: agentic-ai
canonical_id: 01KQ0P44RMTA9YY6DXN54MJ6PZ
title: Knowledge Management With Obsidian
type: article
tags:
- pkm
- obsidian
- zettelkasten
- dataview
- automation
status: active
date: 2025-05-15
summary: A technical guide to Personal Knowledge Management (PKM) using the Zettelkasten method and Obsidian automation via Dataview.
auto-generated: false
---

# Obsidian: Zettelkasten and Programmatic Synthesis

Obsidian is more than a Markdown editor; it is a graph-based integrated development environment (IDE) for thought. For researchers, it enables the transition from static notes to a dynamic, navigable knowledge graph.

## 1. The Zettelkasten Method

The Zettelkasten (Slip-box) method treats every note as an **Atomic Unit of Knowledge**.

### 1.1 Core Principles
*   **Atomicity:** One note = one idea. This allows for maximum reusability and granular linking.
*   **Connectivity:** A note is worthless in isolation. Every new note must link to at least one existing note.
*   **Prose-based Linking:** Links should be embedded in context, explaining *why* two ideas are related, rather than just listed at the bottom.

## 2. Structural Architecture: MOCs and PARA

To prevent the graph from becoming unmanageable "spaghetti," we use structural layers.

*   **MOCs (Maps of Content):** Index notes that act as hubs for a specific topic (e.g., `[[Machine Learning MOC]]`). They provide a curated entry point into the graph.
*   **PARA (Projects, Areas, Resources, Archives):** An organizational framework by Tiago Forte that categorizes notes by their *actionability* rather than just their topic.

## 3. Programmatic Automation: Dataview

The **Dataview** plugin transforms Obsidian into a queryable database. By using YAML frontmatter, you can aggregate data across the entire vault.

### 3.1 Example Query
To list all active research papers tagged with "AI" that haven't been reviewed in 30 days:
```sql
TABLE title, author, date_created
FROM #research/ai
WHERE status = "Active" AND date_reviewed < date(today) - dur(30 days)
SORT date_created DESC
```

## 4. Visualization and Tracker Plugins

*   **Graph View:** Visualizes the topology of your knowledge. Clusters indicate emerging domains or over-connected hubs.
*   **Tracker Plugin:** Automates the tracking of quantifiable data (e.g., word count, habits, or research progress) by scraping values from frontmatter or inline fields.

## 5. Technical Summary Table

| Feature | Obsidian Approach | Benefit |
| :--- | :--- | :--- |
| **Storage** | Local Markdown (.md) | Future-proof, no vendor lock-in |
| **Linking** | Bi-directional `[[ ]]` | Bidirectional context discovery |
| **Schema** | YAML Frontmatter | Queryable metadata (Dataview) |
| **Expansion** | Community Plugins | Extensible functionality (AI/Sync)|

## 6. Summary

Knowledge management with Obsidian is an engineering task. By combining the **Zettelkasten** method for idea generation with **Dataview** for programmatic synthesis, researchers can build a "Second Brain" that scales linearly with their intellectual output.
