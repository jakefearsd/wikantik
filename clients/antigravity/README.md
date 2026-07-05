# Wikantik context briefing — Google Antigravity

## Research findings (2026-07-05)

Antigravity moves fast and its first-party docs (`antigravity.google/docs/*`) are a
JS-rendered SPA that our fetch tooling could not pull raw content from, so the
notes below are triangulated from multiple second-/third-party sources
(blog posts and guides dated March–June 2026) rather than a single canonical
spec. Where sources disagreed, that's called out explicitly. Re-verify against
`antigravity.google/docs/hooks` and `antigravity.google/docs/mcp` before
depending on exact field names in a production setup.

**(a) Session-start hooks / auto-run commands — yes, with caveats.**
Antigravity ships a hooks system (JSON config; one source dates its
introduction to "Antigravity 2.0", unverified against first-party release
notes) with five consolidated lifecycle events: `PreToolUse`, `PostToolUse`,
`PreInvocation`, `PostInvocation`, `Stop`. `PreInvocation` fires **before each
model call** and can inject text into the agent's context — hook scripts read
a JSON payload from stdin (`toolCall.args`, `workspacePaths`,
`transcriptPath`) and print JSON to stdout; `additionalContext` /
`systemMessage` / `reason` fields are newline-joined and appended to the
model's context. One source (a Medium "Developer's Guide to Agent Hooks")
additionally claims a dedicated `SessionStart` event exists, but a second
source covering the same hook system did not mention it and no example
config for it could be found — treat `SessionStart` as **unconfirmed**.
`PreInvocation` is corroborated across sources and is the safer bet: it fires
on every model call rather than once per session, so — exactly like the
Claude Code `UserPromptSubmit` hook in `clients/claude-code/` — the script
must self-gate with a "did I already inject this session" state file keyed
by `transcriptPath`, or the briefing would be re-fetched and re-injected on
every turn.
Hook config lives at `<project_root>/.agents/hooks.json` (workspace,
takes precedence) or `~/.gemini/config/hooks.json` (global) — one source
gave the global path as `~/.gemini/antigravity-cli/hooks.json` instead, so
check both when troubleshooting a given install. Config shape:
```json
{
  "wikantik-briefing": {
    "PreInvocation": [
      { "matcher": "*", "hooks": [ { "type": "command", "command": "/abs/path/to/antigravity-briefing-hook.sh", "timeout": 15 } ] }
    ]
  }
}
```

**(b) MCP server config — yes, well-corroborated.** A single
`mcpServers` object in `mcp_config.json`, global at
`~/.gemini/config/mcp_config.json` or workspace-local at
`.agents/mcp_config.json`. Supports local stdio commands AND remote
servers (a `url`, plus `headers` for bearer/API-key auth) — remote is
what we need for Wikantik's Streamable HTTP `/knowledge-mcp` endpoint. See
"MCP server registration" below.

**(c) Rules files — yes, this is the well-trodden, stable path.** Antigravity
reads `GEMINI.md` (Antigravity-specific, highest priority) and, since
v1.20.3 (dated ~2026-03-05 by one source), a cross-tool `AGENTS.md` at both
global (`~/.gemini/AGENTS.md`) and workspace (`<project_root>/AGENTS.md`)
scope, plus a `.agent/rules/` directory for additional workspace rules.
Every agent spawned in the workspace reads these before starting work. This
mechanism is corroborated by the most sources and is the one every
Antigravity install predating the hooks system will already support, so it's
documented here as the required fallback even after the hooks path lands.

**Given the above, this shim documents both paths, deterministic preferred:**
1. **Preferred — `PreInvocation` hook** (`.agents/hooks.json` +
   `antigravity-briefing-hook.sh`, same shape as the Claude Code hook):
   fires once per session (self-gated on `transcriptPath`) and injects the
   briefing without the agent needing to remember to call anything.
2. **Fallback — rules snippet** (`wikantik-briefing-rules.md`, pasted into
   `AGENTS.md` or `GEMINI.md`): works on every Antigravity version, including
   ones predating the hooks system, but relies on the agent actually
   following the instruction at the start of a session rather than a
   platform guarantee.

Sources consulted (all accessed 2026-07-05; publication dates as noted where
the source gave one):
- [Antigravity Rules: Guide with AGENTS.md & Examples (2026)](https://agentpedia.codes/blog/user-rules) — AGENTS.md / GEMINI.md hierarchy, v1.20.3 dating, `.agent/rules/`.
- [Subagents, Hooks, Scheduled Tasks, Agent Management, Voice, and Much More](https://antigravity.google/blog/google-io-2026-feature-deep-dive) — hooks lifecycle events, `// turbo` auto-run, scheduled tasks.
- [Mastering Hooks in Coding Agents · danicat.dev](https://danicat.dev/posts/20260610-mastering-hooks/) (dated 2026-06-10) — hooks.json schema, stdin/stdout contract, `PreInvocation` used for session-start context injection.
- [A Developer's Guide to Agent Hooks in Antigravity CLI (Medium, Google Cloud Community)](https://medium.com/google-cloud/a-developers-guide-to-agent-hooks-in-antigravity-cli-4c1440febd11) (dated ~2026-06) — five consolidated hook events, `.agents/hooks.json` vs `~/.gemini/antigravity-cli/hooks.json`, `matcher` field, `allow_tool` output.
- [Configuring MCP Servers and Skills for Antigravity CLI and IDE (Medium)](https://medium.com/google-cloud/configuring-mcp-servers-and-skills-for-antigravity-cli-and-ide-a938c7eebb78) (dated May 2026) — `mcp_config.json` location and shape.
- [How to connect MCP servers with Google Antigravity (Composio)](https://composio.dev/content/howto-mcp-antigravity) — remote server `url`/`headers` support.

---

## What this directory provides

Two ways to get a Wikantik context briefing into an Antigravity agent's
context automatically:

1. **`antigravity-briefing-hook.sh`** (deterministic, preferred) — a
   `PreInvocation` hook, structurally identical to
   `clients/claude-code/briefing-hook.sh`: it fetches the briefing over
   **REST** (`GET $WIKANTIK_BASE_URL/api/briefing?format=md`, the same
   endpoint the Claude Code shim uses) once per session and injects the
   returned markdown as `additionalContext`. It does **not** go through the
   MCP server — hooks are shell scripts running outside the agent's tool
   surface, so REST is the right transport here (exactly as it is for the
   Claude Code hook). Requires `bash`, `curl`, and `jq` on the machine
   running Antigravity.
2. **`wikantik-briefing-rules.md`** (fallback, always works) — a snippet to
   paste into the consuming project's `AGENTS.md` (or `GEMINI.md`) that
   instructs the agent to call the `get_briefing` **MCP tool** itself at the
   start of a session.

The rules-snippet fallback (Option 2) and the `clients/skills/wiki-context`
skill both require the Wikantik `/knowledge-mcp` MCP server to be registered
with Antigravity. The hook (Option 1) does not — it only needs the REST env
vars listed below. Register the MCP server anyway: the wiki-context skill's
follow-up tools (`assemble_bundle`, `read_pages`, …) need it regardless of
which briefing-injection path you pick.

## MCP server registration (Option 2 + the wiki-context skill)

Add an entry to `mcp_config.json` (global `~/.gemini/config/mcp_config.json`
or workspace `.agents/mcp_config.json`):

```json
{
  "mcpServers": {
    "wikantik-knowledge": {
      "url": "https://wiki.example.com/knowledge-mcp",
      "headers": {
        "Authorization": "Bearer ${WIKANTIK_MCP_TOKEN}"
      }
    }
  }
}
```

Substitute your real wiki base URL and an MCP access key (minted the same
way as any other Wikantik MCP client — see the main repo's MCP access-key
docs). Once registered, `get_briefing`, `assemble_bundle`, `read_pages`, and
the rest of the `/knowledge-mcp` tool surface are available to the agent —
this is the same tool surface the `clients/skills/wiki-context` skill
consumes, and the skill is MCP-only by rule: agent-driven wiki interaction
never falls back to REST/curl. (The hook script below is the one deliberate
exception — it runs *outside* the agent, before the model call, where MCP
tools aren't available.)

## Option 1: deterministic hook (preferred)

Prerequisites: `bash`, `curl`, and `jq` on the machine running Antigravity
(same as the Claude Code hook).

1. Copy `antigravity-briefing-hook.sh` into the consuming repo:
   ```bash
   mkdir -p clients/antigravity
   cp /path/to/wikantik/clients/antigravity/antigravity-briefing-hook.sh clients/antigravity/
   chmod +x clients/antigravity/antigravity-briefing-hook.sh
   ```
2. Register it as a `PreInvocation` hook in `.agents/hooks.json`:
   ```json
   {
     "wikantik-briefing": {
       "PreInvocation": [
         {
           "matcher": "*",
           "hooks": [
             { "type": "command", "command": "./clients/antigravity/antigravity-briefing-hook.sh", "timeout": 15 }
           ]
         }
       ]
     }
   }
   ```
3. Set env vars the hook reads (identical contract to the Claude Code hook):

   | Variable | Required | Purpose |
   |---|---|---|
   | `WIKANTIK_BASE_URL` | **Yes** | Base URL of the Wikantik instance (e.g. `https://wiki.example.com`). The hook silently no-ops (`{}`) if unset. |
   | `WIKANTIK_BRIEFING_PINS` | No | Comma-separated page names to always include. |
   | `WIKANTIK_BRIEFING_CLUSTERS` | No | Comma-separated clusters to include. |
   | `WIKANTIK_BRIEFING_BUDGET` | No | Token/size budget passed through. |
   | `WIKANTIK_BASIC_AUTH` | No | `user:pass` for HTTP Basic auth (`curl -u`) when the wiki requires it. |

4. The hook self-gates on `transcriptPath` (one state file per session under
   `${XDG_CACHE_HOME:-$HOME/.cache}/wikantik-briefing/`), matching the
   Claude Code shim's "once per session, never fails the turn" behavior: if
   the REST fetch fails or times out (10s), the hook exits 0 with a no-op
   `{}` response and the model call proceeds unmodified.

Because `PreInvocation` fires on *every* model call (there is no confirmed
dedicated session-start event — see Research findings above), the state-file
gate is load-bearing, not an optimization: without it the briefing would be
re-injected on every single turn.

## Option 2: rules-snippet fallback

If the target Antigravity install predates the hooks system, or you'd rather
not run a shell hook, paste the contents of `wikantik-briefing-rules.md`
into the project's `AGENTS.md` (or `GEMINI.md`). This asks the agent to call
`get_briefing` itself as its first action — reliable in practice (agents
follow standing instructions well) but not a platform-enforced guarantee the
way the hook is.

## Manual test

With a Wikantik instance reachable at `$WIKANTIK_BASE_URL`:

```bash
chmod +x clients/antigravity/antigravity-briefing-hook.sh
echo '{"transcriptPath":"/tmp/test-session-1.log","prompt":"how does billing work"}' | \
  WIKANTIK_BASE_URL=http://localhost:8080 WIKANTIK_BRIEFING_PINS=Main \
  ./clients/antigravity/antigravity-briefing-hook.sh
```

Expected: a JSON object on stdout with `additionalContext` set to the
briefing markdown (starting `# Wiki context briefing`). Running the same
command again with the same `transcriptPath` prints `{}` (state file already
exists — one injection per session). Reset with
`rm ~/.cache/wikantik-briefing/_tmp_test-session-1.log.done` (the session
key is the `transcriptPath` with `/` replaced by `_`), or clear all cached
sessions with `rm -rf ~/.cache/wikantik-briefing`.

Failure path (unreachable wiki, or `WIKANTIK_BASE_URL` unset): the hook
prints `{}` and exits 0 — the silent no-op degrade is the correct behavior,
never an error that blocks the model call:

```bash
echo '{"transcriptPath":"/tmp/test-session-2.log","prompt":"x"}' | \
  WIKANTIK_BASE_URL=http://localhost:1 ./clients/antigravity/antigravity-briefing-hook.sh
echo "exit=$?"   # {} on stdout, exit=0
```
