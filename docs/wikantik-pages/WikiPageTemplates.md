---
canonical_id: 01KQ0P44Z2HDXRY0F7FHWRHSYJ
title: Wiki Page Templates
type: article
cluster: wikantik-development
status: active
date: '2026-04-26'
summary: How to design page templates that make wiki content consistent — common
  page types, structural patterns, and the trade-offs between rigid templates and
  free-form pages.
tags:
- wiki
- templates
- consistency
- wikantik-development
related:
- WikiContentManagementWorkflow
- WikiSearchOptimization
---
# Wiki Page Templates

Templates are pre-defined page structures. New pages start from a template; have predictable sections.

Done well, templates make wikis consistent. Done poorly, they're cargo-cult forms nobody fills in correctly.

## Common page types and their templates

### Concept page

Describes a thing.

```markdown
# {{ConceptName}}

## What it is
[brief description]

## Why it matters
[context]

## How it works
[technical details]

## Examples
[concrete examples]

## See Also
[related pages]
```

For documentation wikis, this is the workhorse template.

### How-to page

Step-by-step guide for a specific task.

```markdown
# How to {{Task}}

## Prerequisites
- [list]

## Steps
1. [step]
2. [step]
3. [step]

## Verification
[how to confirm it worked]

## Troubleshooting
[common issues]
```

### Reference page

Detailed technical reference.

```markdown
# {{API}} Reference

## Overview
[summary]

## Parameters
[table or list]

## Returns
[description]

## Examples
[examples]

## Errors
[error conditions]
```

### Decision record (ADR)

```markdown
# ADR-{{number}}: {{title}}

## Status
[Proposed | Accepted | Deprecated]

## Context
[why this came up]

## Decision
[what we decided]

## Consequences
[implications]
```

### Meeting notes

```markdown
# {{Meeting}} - {{Date}}

## Attendees
[list]

## Agenda
[items]

## Decisions
[items]

## Action Items
[owner: action]
```

### Runbook

```markdown
# Runbook: {{Alert/Issue}}

## Symptoms
[how you know this is happening]

## Initial actions
[immediate response]

## Common causes
[list]

## Resolution
[per-cause fixes]

## Escalation
[when and to whom]
```

## Design principles

### Match the content

The template should match how the content actually wants to be structured. Don't force "How to" template onto a "Concept" page.

### Optional sections

Not every section applies to every page. Templates should accommodate that — some sections are optional, marked as such.

### Frontmatter conventions

Templates often include standard frontmatter:

```yaml
---
title: ...
type: concept | howto | reference | adr | meeting | runbook
status: draft | published
last_reviewed: YYYY-MM-DD
---
```

Frontmatter type field enables filtering, indexing, sorting.

### Examples within the template

Show what each section should look like. Example fills the template; users replace with their content.

### Style guide consistency

Templates encode style choices: heading hierarchy; list style; code block formatting. Consistency across pages.

## Trade-offs

### More templates → more consistency

Pages following templates look similar. Easier to navigate.

### More templates → more rigid

Some content doesn't fit any template. Forcing fit creates awkward pages.

### Self-discoverable

For new contributors, templates show "this is the format" — reduces guesswork.

### Imposes process

Templates are friction. For low-stakes content, may slow down contribution.

## Implementation patterns

### Wiki-native template support

Many wikis support templates: MediaWiki templates, Confluence templates, etc. Use the platform's mechanism.

### Skeleton files

For markdown-based wikis: skeleton files copied when creating new pages. Tools support this (e.g., Hugo, Jekyll).

### Frontmatter validation

Schema validation that frontmatter has required fields. Pages without proper frontmatter rejected.

### Template inheritance

A "child template" extends a "parent template" — reduces duplication when templates have common structure.

### Templates as documentation

The template itself serves as documentation: "this is the structure we use."

## When to add a new template

The pattern: when you've seen the same structure 3+ times across pages, it's a template candidate.

Don't preemptively design 20 templates. Let usage show what's needed.

## When to skip templates

### Free-form pages

Some content doesn't fit a structure. Don't force.

### Small wikis

For a wiki with 50 pages, individual variation is fine.

### Highly diverse content

If most pages are unique in shape, templates don't help.

## Common failure patterns

### Templates nobody uses

Created; nobody fills them in. Maybe wrong structure; maybe undiscoverable.

Use analytics: are templates being used? If not, why?

### Cargo-cult template filling

Author fills sections without thinking. "Examples: TBD" everywhere.

Templates should guide thought, not just provide structure to fake-fill.

### Too many templates

10 templates for slightly different page types. Author can't decide; uses wrong one; gives up.

Fewer templates with more flexibility usually wins.

### Outdated templates

Template hasn't been updated; uses old style. Pages from this template look dated.

Periodic template review.

### Misaligned templates

Templates don't match what good pages on this wiki actually look like. Authors use templates and produce inferior pages.

Look at the best existing pages; templates derive from them.

## Specific patterns

### Linked from "create page"

Make templates discoverable. The "create new page" flow lists templates.

### Auto-population

Some metadata can be auto-filled: author (the creator); date (today); template (chosen).

### Linting

Automated checks: frontmatter complete; required sections present; no "TBD" in published pages.

### Version templates

When template changes, mark when. New pages use new version; old pages may be outdated.

## A reasonable starter

For new wikis:

1. 2-3 core templates: concept, how-to, reference
2. Add specific templates as needed (ADR, runbook, etc.)
3. Frontmatter schema with required fields
4. Templates discoverable from page-create flow
5. Periodic review and update

For existing wikis without templates: extract patterns from existing high-quality pages; codify.

## Further Reading

- [WikiContentManagementWorkflow](WikiContentManagementWorkflow) — Editorial workflow
- [WikiSearchOptimization](WikiSearchOptimization) — Templates affect findability
