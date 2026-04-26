---
title: Ai For Code Review
type: article
cluster: agentic-ai
status: active
date: '2026-04-25'
tags:
- code-review
- ai-coding
- pr-review
- static-analysis
summary: Using LLMs as a first-pass code reviewer — what they catch, what they
  miss, and the workflow patterns that make AI review augment rather than
  replace human review.
related:
- AiPairProgramming
- AiForSoftwareTesting
- CodeReviewPractices
hubs:
- AgenticAi Hub
---
# AI for Code Review

Code review's purpose is two-fold: catch bugs before merge, and share knowledge so the team grows together. AI is good at the first; it cannot replace the second. The right model: AI as a first-pass reviewer that catches the easy stuff so humans can focus on the architectural and contextual concerns.

This page is the working pattern.

## What AI code review actually catches

In good evaluations:

- **Style violations** — formatting, naming inconsistencies. Strong; nearly perfect.
- **Common bugs** — null pointer, off-by-one in obvious cases, incorrect loop bounds. Decent; misses subtle ones.
- **Security issues** — SQL injection, XSS, hardcoded secrets, weak crypto. Decent; better with security-focused prompting.
- **Missing error handling** — uncaught exceptions, unchecked return values. Decent.
- **Logic errors that contradict comments / docstrings** — doc says "returns N+1," code returns N. Decent.
- **Missing tests for new code paths** — surface-level; doesn't know your test discipline.
- **Performance concerns** — quadratic loops over collections, unnecessary DB queries. Hit and miss.

What it misses:

- **Architectural fit** — "this should be a separate service" / "this couples X to Y inappropriately." Models don't have your codebase's bigger picture.
- **Domain correctness** — "this discount calculation doesn't match our pricing policy."
- **Subtle race conditions** — concurrent code is hard for both models and humans.
- **Performance issues that require profiling** — a model can spot O(n²); it can't tell you that this O(n) operation runs 50× per request and adds 200ms.
- **Issues that span the diff context** — interactions with code not in the PR.

This is why AI review is "first pass" not "the review."

## The workflow patterns that work

### PR-time AI review

Bot runs on every PR. Posts comments with suggestions. Author reviews; takes valid ones; ignores false positives.

```
On PR open:
  ai_review(diff, repo_context) -> [list of comments]
  for each comment: post inline on PR
  summary comment: "Reviewed by [bot]. N suggestions. Examine before merge."
```

Tools that do this:

- **GitHub Copilot for PRs / Copilot Workspace** — integrated.
- **CodeRabbit** — popular; multi-language.
- **Greptile, Codium PR-Agent, ellipsis.dev** — competitors.
- **Custom bots using Claude or GPT** — easy to build for organisation-specific concerns.

### Pre-commit / pre-push AI review

Runs locally before commit. Catches things before the PR.

```
git pre-push hook: 
  diff = git diff origin/main..HEAD
  ai_review(diff) -> findings
  prompt user to address before pushing
```

Trade-off: more friction in dev loop; catches issues earlier; reduces PR noise.

### "Explain this PR" for human reviewers

Human is reviewing a PR; asks AI to summarise the changes, flag risky parts, suggest what to focus on:

```
You: What changed in this PR? What should I be careful about?
AI: This PR adds support for async refunds. The risky parts:
  1. The refund retry logic in payment_service.go uses a fixed
     2-second delay which doesn't match the rest of the codebase's
     exponential backoff pattern.
  2. The new database column is NOT NULL but the migration doesn't
     backfill existing rows — this will fail in environments with
     existing data.
  3. The test in test_refunds.py tests the happy path but no error 
     paths.
```

This pattern *amplifies* human review without replacing it. The human still decides; the AI helps them allocate attention.

## Prompting for review

A naïve prompt ("review this code") gets generic feedback. Better:

```
You are reviewing code for a financial-services backend. Focus on:
- Correctness of the business logic
- Security vulnerabilities (esp. around money handling)
- Concurrency / race conditions
- Match with existing patterns in the codebase

Here is the diff:
{diff}

Here are related files for context:
{neighbouring code}

Output:
- Severity: blocking | should-fix | optional
- Line: specific line in the diff
- Concern: one paragraph
- Suggestion: concrete code change if applicable

Don't comment on style or formatting (handled separately).
Don't comment on things you can't see (architecture not in this diff).
If everything looks good, say so.
```

Categorising by severity matters — without it, every observation gets flagged equally and the human can't filter.

Including context (neighbouring code) is essential. Without it, the model can't tell whether a function "should" be called by the caller or whether the caller is wrong.

## False positives

AI reviewers produce false positives. Common categories:

- **"Add error handling"** for code that intentionally lets the caller handle errors.
- **"Inefficient loop"** for code that runs once, on small data.
- **"Missing test"** for code that's tested in the integration suite, not in the unit tests touched by the PR.
- **"Style inconsistency"** for code that's intentionally different (e.g., legacy file using older patterns).

Mitigations:

- **Pre-filter at known-safe categories.** Run formatters / linters as a first pass; AI only sees what they don't catch.
- **Severity gating.** Only show "blocking" findings inline; collapse "optional" into a summary.
- **Feedback loop.** Authors can mark findings as "false positive"; track per-rule false-positive rate; tune.

A reviewer that produces 30% noise gets ignored. Tune toward fewer high-confidence findings.

## What to tune toward

- **Block on real issues, not nits.** A bot that comments on every lint deviation will be muted.
- **Domain-aware.** Customise for your stack and patterns. Generic AI review is generic.
- **Fast.** PR review delays merge; review feedback under 1 minute.
- **Augmenting, not replacing.** Human review is still the final word.
- **Learning.** Track which findings the team accepts vs ignores; refine the prompt.

## Risks of over-reliance

A team that fully delegates code review to AI will produce more bugs than a team that uses it as one signal. Specific risks:

- **Over-confidence in AI approval.** "The bot said it looks good" → human reviewer rubber-stamps. Reviewers must still do their job.
- **Surface-level feedback only.** AI catches the easy bugs; teams stop catching the hard bugs because nobody's looking for them anymore.
- **Skill atrophy.** Junior engineers who rely entirely on AI feedback don't learn to review code well themselves.

The discipline: AI review is part of the review process. Human review is also part of the review process. The goal is faster + better, not faster instead of better.

## Security-specific review

For security-sensitive PRs, dedicated security-focused prompts catch more:

```
Review this code for security issues. Focus on:
- Input validation and sanitisation
- Authentication and authorisation
- Injection (SQL, command, template, XSS)
- Cryptographic mistakes
- Secret management
- Race conditions in security-relevant code
- Path traversal, SSRF
```

Pair with SAST tools (Semgrep, CodeQL). The combination catches more than either alone.

## ROI and adoption

Teams that have adopted AI code review well report:

- 20-40% reduction in bugs reaching staging.
- Code review feedback faster; faster merge cadence.
- Junior engineers learn faster (they get feedback the moment they push, not days later when a senior has time).

Teams that adopted AI code review poorly report:

- Lots of bot noise; team disabled it.
- False sense of security; bugs still landing in prod.
- Reviewer engagement dropped; quality dropped.

The difference is workflow design, not tool choice.

## Further reading

- [AiPairProgramming] — using AI during code creation
- [AiForSoftwareTesting] — AI in testing specifically
- [CodeReviewPractices] — broader code review discipline
