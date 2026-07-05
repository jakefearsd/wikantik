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
Antigravity ships a hooks system (JSON config, introduced with "Antigravity
2.0") with five consolidated lifecycle events: `PreToolUse`, `PostToolUse`,
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
   `clients/claude-code/briefing-hook.sh`: calls the Wikantik `/knowledge-mcp`
   `get_briefing` MCP tool once per session and injects the returned markdown
   as `additionalContext`.
2. **`wikantik-briefing-rules.md`** (fallback, always works) — a snippet to
   paste into the consuming project's `AGENTS.md` (or `GEMINI.md`) that
   instructs the agent to call `get_briefing` itself at the start of a
   session.

Both rely on the Wikantik `/knowledge-mcp` MCP server being registered with
Antigravity — do that first.

## MCP server registration

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
consumes, and it's MCP-only: no REST/curl fallback is documented or needed.

## Option 1: deterministic hook (preferred)

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
3. Set env vars the hook reads (mirrors the Claude Code hook's contract):
   `WIKANTIK_BRIEFING_PINS`, `WIKANTIK_BRIEFING_CLUSTERS`,
   `WIKANTIK_BRIEFING_BUDGET`. The MCP server itself is reached via the
   `wikantik-knowledge` entry registered above — no base URL or auth
   duplication needed in the hook's own env.
4. The hook self-gates on `transcriptPath` (one state file per session under
   `${XDG_CACHE_HOME:-$HOME/.cache}/wikantik-briefing/`), matching the
   Claude Code shim's "once per session, never fails the turn" behavior: if
   the MCP call fails or times out, the hook exits 0 with no output and the
   model call proceeds unmodified.

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

With the `wikantik-knowledge` MCP server registered and reachable, and a
recent Antigravity build that supports `.agents/hooks.json`:

```bash
chmod +x clients/antigravity/antigravity-briefing-hook.sh
echo '{"transcriptPath":"/tmp/test-session-1.log","prompt":"how does billing work"}' | \
  WIKANTIK_BRIEFING_PINS=Main ./clients/antigravity/antigravity-briefing-hook.sh
```

Expected: a JSON object on stdout with `additionalContext` set to the
briefing markdown (starting `# Wiki context briefing`). Running the same
command again with the same `transcriptPath` prints nothing (state file
already exists). Reset with
`rm ~/.cache/wikantik-briefing/test-session-1.done`.
