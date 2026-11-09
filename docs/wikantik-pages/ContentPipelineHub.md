---
audience:
- humans
type: hub
summary: 'Content pipeline hub: AI Discovery topic universes, the five-agent AI Publisher,
  and the separate Blogger Agent, with how each works and how they connect.'
related:
- AiDiscoveryTopicUniverses
- AiPublisherPipeline
- BloggerAgentWorkflow
- SoftwareProjectsHub
- WikantikDevelopmentHub
title: 'Content Pipeline Hub: Multi-Agent Workflows, Fact Verification, and Automated
  Publishing'
cluster: content-pipeline
tags:
- content-pipeline
- agentic-ai
- multi-agent
- publishing
- fact-checking
- automation
- wikantik-development
date: '2026-08-08'
canonical_id: 01KZG25PXFNJ8RTDDK1EXQSHQV
status: active
---
# Content Pipeline Hub: Multi-Agent Workflows, Fact Verification, and Automated Publishing

The content pipeline is a set of three small tools. Each lives in its own GitHub repository under `jakefearsd`, and none of them is part of the Wikantik codebase. Wikantik does not call them: they hand work to each other, and to a wiki or a blog, through files and publishing APIs.

---

## 1. Quick-Reference: The Three Projects

| Project | Role | Stack | Output |
|---------|------|-------|--------|
| AI Discovery (`aidiscovery`) | Plans a *topic universe*: the topics, relationships and priorities for a wiki | Java 21, Spring Boot CLI, LangChain4j; Anthropic Claude or Ollama; Wikidata/Wikipedia grounding | `~/.aipublisher/universes/<id>.universe.json` |
| AI Publisher (`aipublisher`) | Writes articles through a five-agent pipeline | Java 25, Spring Boot 3.5, LangChain4j; Ollama (default) or Anthropic Claude | JSPWiki-syntax `.txt` files in `./output` |
| Blogger Agent (`blogger-agent`) | Researches, drafts, illustrates and publishes posts to four Google Blogger blogs | Agent Skills (`SKILL.md`) plus Python scripts over the Blogger API v3 | Live Blogger posts, plus `content_calendar.md` and `social_drafts.md` |

---

## 2. How the Pieces Connect

- AI Discovery saves a topic universe as JSON. AI Publisher reads it with `--universe <id>` and writes one article per accepted topic, optionally filling broken wiki links with short stub definitions.
- AI Publisher's output is JSPWiki syntax, not Markdown. To bring those files into a Markdown wiki such as Wikantik, `bin/wiki2markdown.py <directory>` in the Wikantik repository converts JSPWiki `.txt` files to `.md` (it deletes the originals and has a `--dry-run` flag).
- Blogger Agent is independent. It publishes HTML straight to Google Blogger and uses neither of the other two tools.

## 3. The AI Publisher Agents

| Agent | Responsibility | Runs by default? |
|-------|----------------|------------------|
| Research | Gathers facts and sources, builds an outline, grounded with Wikidata | Yes |
| Writer | Drafts the article in JSPWiki syntax | Yes |
| Fact Checker | Verifies claims against the sources; approves, asks for a revision, or rejects | No (`pipeline.skip-fact-check=true`) |
| Editor | Polishes prose, integrates wiki links, scores quality | Yes |
| Critic | Reviews JSPWiki syntax, structure and readability | No (`pipeline.skip-critique=true`) |

---

## 4. Core Pipeline Guides

- **[AiDiscoveryTopicUniverses](AiDiscoveryTopicUniverses)**: How AI Discovery builds a topic universe with LLM suggestions, Wikidata grounding and gap analysis.
- **[AiPublisherPipeline](AiPublisherPipeline)**: The five-agent article pipeline, its skip defaults, revision loop and output format.
- **[BloggerAgentWorkflow](BloggerAgentWorkflow)**: The skill-driven workflow that drafts and publishes posts to Google Blogger.
- **[TheLocalWikiBloggerSites](TheLocalWikiBloggerSites)**: The Blogger-hosted sites that workflow publishes to.
