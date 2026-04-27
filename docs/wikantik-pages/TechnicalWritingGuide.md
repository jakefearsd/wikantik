---
canonical_id: 01KQ0P44XED3VY3PK9EX77MHD9
title: Technical Writing Guide
type: article
cluster: software-engineering-practices
status: active
date: '2026-04-26'
summary: Practical technical writing for engineers — the document types that matter
  (design docs, runbooks, postmortems), the patterns that make them useful, and the
  habits that let writing become routine rather than exceptional work.
tags:
- technical-writing
- documentation
- design-docs
- software-engineering
- communication
related:
- CodeDocumentationBestPractices
- EngineeringDecisionFrameworks
- TechnicalLeadershipSkills
- LegacyCodeModernization
hubs:
- SoftwareEngineeringPractices Hub
---
# Technical Writing Guide

Engineers who write well have a structural advantage. Their designs get reviewed by more people; their decisions stick because they're documented; their incidents produce learning rather than recurrence. Writing well is not a separate skill from engineering — it is part of the work.

This page is about the document types that matter, the patterns that make them useful, and the habits that turn writing into routine rather than ad hoc work.

## The principle

Technical writing optimizes for the reader, not the writer. The reader has limited attention, may be reading later, may not have the context the writer has, and may not need every detail.

Specific principles that follow:

- **Lead with the conclusion.** Most readers don't read past the first paragraph; that paragraph should contain the bottom line.
- **Front-load the most important information.** Each section should be useful even if the reader stops after one paragraph.
- **Cut anything that doesn't earn its place.** Length is not a virtue.
- **Use concrete language.** "Faster" is vague; "200ms vs. 400ms" is concrete.
- **Show, don't tell.** Examples, code, diagrams beat abstract description.

## The four documents that matter most

### Design documents

A design doc proposes a solution to a problem before implementation begins. The structure that works:

1. **Title and one-paragraph summary.** What is being decided.
2. **Context.** Why is this work being done?
3. **Goals and non-goals.** What are we trying to achieve; what are we explicitly not addressing?
4. **Proposed approach.** What we plan to do.
5. **Alternatives considered.** What other approaches we evaluated and why we rejected them.
6. **Risks and open questions.** What could go wrong; what we still need to figure out.
7. **Plan.** Sequencing, milestones, owners.

A good design doc is 2–10 pages. Anything longer is usually under-edited.

### Runbooks

A runbook is operational documentation — what to do when something happens. The structure:

1. **Symptoms.** What does the problem look like? Specifically the alert, the error message, the customer report.
2. **Initial actions.** First diagnostic steps. Specific commands.
3. **Common causes.** What usually causes this; how to identify which one.
4. **Resolution per cause.** Specific steps to fix.
5. **Escalation.** Who to call if the runbook doesn't help.

Runbooks are dramatically more useful than abstract architecture documentation in incident scenarios. The on-call engineer at 3am needs commands, not theory.

### Postmortems

After an incident, a postmortem documents what happened and why. The structure:

1. **Summary.** What happened, what was the impact.
2. **Timeline.** Specific events and times.
3. **Root cause.** Not blame; the underlying mechanism.
4. **Contributing factors.** What made the problem possible.
5. **What went well.** Detection, response, mitigation.
6. **What could go better.** Improvement areas.
7. **Action items.** Specific changes with owners.

The cultural element matters. Blameless postmortems — focused on systems, not individuals — produce learning. Blame postmortems produce defensiveness.

### Architecture Decision Records (ADRs)

A short document capturing a single architectural decision:

1. **Title.**
2. **Status.** Proposed, accepted, deprecated, etc.
3. **Context.** Why we needed to decide.
4. **Decision.** What we chose.
5. **Consequences.** What this commits us to and what it precludes.

ADRs are typically 1 page. They live alongside code; they preserve the *why* of decisions for future engineers who weren't around for the original choice.

## Patterns that make documents useful

### The TL;DR

A two-sentence summary at the top. The reader knows whether to keep reading.

### Concrete examples

Instead of "the system handles many request types" — list 3–5 specific request types with their characteristics. The abstract claim is forgettable; the specific examples stick.

### Code over prose

A code block illustrating a pattern is worth a paragraph describing it. Where code can show what you mean, use it.

### Diagrams for spatial concepts

Architecture, data flow, dependency graphs are easier to understand visually. ASCII art is fine; Mermaid diagrams are better; even a hand-drawn diagram in a screenshot beats an abstract paragraph.

### Lists for parallel items

When you have N parallel items, list them. Embedding 5 items in a paragraph hides the parallelism.

### Concrete recommendations

"You should consider X" is weak. "Use X when Y; use Z when W" is actionable.

## The writing process that works

### Outline first

Before writing prose, write the section headings and the one-sentence summary of each. The structure should be apparent from the outline; if it isn't, the structure is wrong.

### Write the first draft fast

Don't edit while writing the first draft. Get the ideas out. The structural problems and missing details become visible after the draft exists.

### Edit ruthlessly

Most first drafts are 20–40% too long. Cut sections that don't earn their place. Combine sections that overlap. Tighten sentences.

### Get feedback from intended readers

A senior engineer's feedback on a design doc is different from a junior engineer's feedback. Both are valuable; both reveal different blind spots. Get feedback from the actual readers.

### Iterate

Multiple drafts beat one polished attempt. The first draft establishes the shape; subsequent drafts make it sharp.

## Specific patterns to avoid

### Hedging language

"Maybe we could perhaps consider..." — say what you think. If you're uncertain, say "this is uncertain because X."

### Unnecessary jargon

Use precise technical terms when they're correct, but don't use jargon to sound expert when plain words work.

### Long preamble

"As we all know, software systems have many components, and one important consideration is..." — get to the point.

### Conclusions as restatement

A "conclusion" that restates the introduction adds nothing. Either say something new (a synthesis, a recommendation, next steps) or omit it.

### Passive voice without reason

"It was determined that..." — by whom? Active voice is usually clearer.

## Documents that don't earn their place

Some documents get written that probably shouldn't:

- **Documentation that duplicates code or comments.** If the code says it, prose repeating it is decay-prone.
- **Status reports that nobody reads.** Status that nobody acts on is overhead.
- **Process documents that everyone ignores.** A process documented but not enforced is fiction.
- **Multi-hundred-page architecture documents.** Nobody reads them; they decay quickly.

If a document type isn't producing value, stop producing it.

## Writing as routine practice

The engineers who write well typically:

- Write something every day (a comment, a commit message, a doc, a postmortem)
- Edit their own writing before sharing
- Read other engineers' writing critically
- Take feedback seriously and revise

Writing is a skill that improves with practice. Like any skill, the way to improve is the boring way: do it more, get feedback, revise.

## Common failure patterns

- **Long documents written once, never updated.** They become wrong; future readers trust them and act on stale information.
- **No distinction between document types.** Treating a runbook like a design doc fails both purposes.
- **Writing for yourself, not the reader.** Documents the author understands but readers don't.
- **Skipping the outline.** Produces structurally weak prose.
- **Avoiding writing.** Engineers who don't write rarely improve.
- **Excessive formality.** Sometimes a Slack message is the right format; not everything needs a formal document.

## Further Reading

- [CodeDocumentationBestPractices](CodeDocumentationBestPractices) — Comments and inline documentation
- [EngineeringDecisionFrameworks](EngineeringDecisionFrameworks) — How design docs fit into decisions
- [TechnicalLeadershipSkills](TechnicalLeadershipSkills) — Writing as leadership practice
- [LegacyCodeModernization](LegacyCodeModernization) — Documentation in modernization
- [SoftwareEngineeringPractices Hub](SoftwareEngineeringPractices+Hub) — Cluster index
