# Wikantik — Organic Launch & Visibility Plan

> Free / organic visibility before any paid marketing. Open-core/freemium model:
> free self-host is top-of-funnel, paid hosted/support is the conversion.
> Lead every post with the **agent-native** hook.

## 0. Nail the hook first (do before posting anywhere)

One sharp sentence. Wikantik's wedge isn't "another wiki" — it's:

> **"A self-hosted wiki that's built to be read by AI agents — native MCP servers,
> hybrid retrieval, and RAG-ready page projections out of the box."**

"Open-source wiki engine" = crowded, sleepy. "MCP-native knowledge base" = live, hungry.

## Audience priority (for open-core + all three audiences)

1. **AI/agent builders first** — sharpest, least-crowded wedge. MCP-native is novel.
   Channels: LocalLLaMA / MCP communities, Show HN, awesome-mcp.
2. **Self-hosters second** — huge volume, matches the free tier, drives stars + word-of-mouth.
   Channels: r/selfhosted, awesome-selfhosted, AlternativeTo.
3. **Dev teams last (but they're the money)** — convert to paid hosting/support, reached
   via SEO ("Confluence alternative") + earned credibility, not cold posts.
   Channels: cornerstone blog posts, IndieHackers build log.

Rules of thumb: lead with the agent-native hook; make free self-host dead simple;
keep paid hosting a quiet "or let us host it" — never the headline. OSS communities
punish a paywall-forward pitch.

## 1. Launch / discovery platforms (one-time spikes)

Space these deliberately so each points to the last.

| Platform | Why it fits | Notes |
|---|---|---|
| **Show HN** | Self-hosted + AI-agent angle is catnip | Lead with the technical story, not a pitch |
| **Product Hunt** | Good for landing page + email capture | Lower dev cred than HN but real traffic |
| **Lobste.rs** | Smaller, higher-signal dev crowd | Needs an invite; AI/retrieval content does well |
| **awesome-* GitHub lists** | `awesome-selfhosted`, `awesome-mcp`, `awesome-rag` | A PR = durable, compounding discovery |
| **AlternativeTo / SaaSHub** | "Confluence alternative" / "self-hosted Notion" searchers | Set-and-forget listings |

## 2. Communities (ongoing, highest ROI for solo)

Show up, answer questions, link only when genuinely relevant.

- **r/LocalLLaMA & MCP/Claude-tooling communities** — best-fit audience right now.
- **r/selfhosted** — perfect audience; honest, screenshot-heavy posts do well.
- **r/ObsidianMD, r/Notion, r/PKMS** — people frustrated with closed tools.
- **MCP / Anthropic developer Discord & forums** — real MCP servers = legit contribution.
- **Indie Hackers** — build log / bootstrapping story; transparency attracts an audience.

## 3. Owned content (slow compounding moat)

SEO + sitemap + per-page titles already shipped — feed the engine:

- **Use Wikantik's own wiki as the marketing.** Public, well-structured pages on "MCP",
  "RAG-ready documentation", "Page Graph vs Knowledge Graph" rank *and* demo the product.
  Dogfooding is the demo.
- **2–4 cornerstone posts**: "self-hosted Confluence alternative", "how to make a wiki
  RAG-ready", "giving an AI agent access to your docs via MCP".
- **One short demo video / GIF** (agent querying the wiki live) — reused in every post.

## 4. Direct & niche (low volume, high intent)

- A few targeted devs/teams who'd use an agent-readable KB — DM/email, offer setup help.
- Useful comments on HN / blog threads about RAG, MCP, internal docs — link only when it answers.

## Realistic solo cadence

1. **Week 1–2:** Sharpen one-liner, record demo GIF, polish GitHub README + landing page.
2. **Week 3:** awesome-list PRs + AlternativeTo listings (durable, low effort).
3. **Week 4:** First real post in LocalLLaMA/MCP or r/selfhosted — measure response.
4. **Then:** Show HN after ironing out feedback (you get one good shot).
5. **Ongoing:** one genuine community interaction/week + one cornerstone page/post/month.

---

## Draft — Show HN

**Title** (factual, no hype):

> Show HN: Wikantik – a self-hosted wiki built to be queried by AI agents (MCP)

**Body:**

> I've been building Wikantik, a self-hosted wiki engine with a twist: it's designed from the ground up to be read by AI agents, not just humans.
>
> Most wikis bolt on an "AI search" feature. I went the other way — the wiki exposes native MCP servers so an agent (Claude, etc.) can do hybrid retrieval (BM25 + dense vectors + a co-mention knowledge graph), traverse the page graph, and pull token-budgeted page projections instead of raw markdown dumps. There's also a `/wiki/{slug}?format=md|json` endpoint and an incremental change feed for RAG ingestion.
>
> Some of the things that turned out to be interesting to build:
> - A "page graph" (real wikilinks) kept deliberately separate from a "knowledge graph" (LLM-extracted entities + relations) — conflating them was my first mistake.
> - An agent-grade content layer: pages carry verification metadata (verified_at, confidence, audience) and a `/for-agent` projection that returns summary + key facts + tool hints rather than the whole body.
> - Lucene HNSW for in-process ANN after brute-force vector scan ate ~60% of search CPU.
>
> It's open-core — free to self-host (Java/Postgres, Docker compose), with a paid hosted option if you don't want to run it yourself.
>
> Repo: [link]  •  Live demo wiki (it documents itself): [link]
>
> Happy to go deep on the retrieval or MCP design in the comments. What I'd most like feedback on: does the agent-native framing resonate, or is "wiki" the wrong word for what this has become?

**Tips:** post Tue–Thu ~8–10am ET. The closing question drives comments (which drive
ranking). Reply to *every* comment for the first 2 hours. Don't seed upvotes — HN
detects and penalizes it.

---

## Draft — r/selfhosted

**Title:**

> I built a self-hosted wiki that AI agents can actually query (MCP-native) — open source

**Body:**

> After getting frustrated that my self-hosted docs were invisible to the LLM tools I use daily, I built **Wikantik** — a wiki engine that's first-class for both humans and AI agents.
>
> **What it is:** a self-hostable wiki (Java + PostgreSQL, runs via Docker compose) with a React frontend — reader, editor, admin panel.
>
> **What's different:**
> - 🤖 **Native MCP servers** — point Claude or any MCP client at it and it can search and traverse your wiki directly.
> - 🔎 **Hybrid retrieval** — BM25 + vector embeddings + a knowledge graph, not just keyword search.
> - 📡 **RAG-ready** — raw markdown/JSON per page + an incremental change feed for feeding other tools.
> - 🔐 The usual self-hoster table stakes: fine-grained ACLs, groups, SSO (OIDC/SAML), database-backed policy.
>
> **Self-hosting:** free and open source. `docker compose up`, point it at Postgres, done. (There's a paid hosted tier if you'd rather not run it — but the self-host path is the whole product, not a crippled demo.)
>
> [screenshot: reader view]
> [screenshot/GIF: agent querying the wiki live]
> [screenshot: knowledge graph viewer]
>
> Repo + docs: [link]
>
> Would love feedback from this crowd specifically — what would make you trust this over a Wiki.js / BookStack / Outline setup? And is the AI-agent angle useful to you, or noise?

**Tips:** the GIF of an agent querying the wiki is your single most important asset —
lead with it. Be transparent about the paid tier (this sub hates hidden upsells; naming
it openly builds trust). Post weekday morning US time. Answer the "why not Wiki.js"
question before they ask it.

---

## Asset checklist (fill the placeholders)

- [ ] Repo link
- [ ] Live demo wiki link
- [ ] Landing page polished (www.wikantik.com)
- [ ] GitHub README leads with agent-native hook
- [ ] Demo GIF: agent querying the wiki live  ← highest-leverage asset
- [ ] Screenshot: reader view
- [ ] Screenshot: knowledge graph viewer

## Next drafts to write (optional, not yet done)

- [ ] awesome-list PR entries (awesome-selfhosted, awesome-mcp) — cheap, compounding
- [ ] LocalLLaMA / MCP-community post (more technical, protocol-detail-forward) — #1 audience
- [ ] Cornerstone blog post drafts (RAG-ready / Confluence-alternative SEO)
