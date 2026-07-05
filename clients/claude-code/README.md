# Wikantik context briefing — Claude Code hook

A `UserPromptSubmit` hook for [Claude Code](https://claude.com/claude-code) that
injects a Wikantik context briefing (`GET /api/briefing?format=md`) into the
model's context once per session — no manual "go read the wiki first" prompt
needed.

## Installation

1. Copy `briefing-hook.sh` into the consuming repo (or reference this file
   absolutely if you keep a shared checkout of Wikantik) — e.g.:

   ```bash
   mkdir -p clients/claude-code
   cp /path/to/wikantik/clients/claude-code/briefing-hook.sh clients/claude-code/
   chmod +x clients/claude-code/briefing-hook.sh
   ```

2. Requires `bash`, `curl`, and `jq` on the machine running Claude Code.

3. Add the hook and its environment to `.claude/settings.json` (project or
   user scope — see [Claude Code hooks docs](https://docs.claude.com/en/docs/claude-code/hooks)):

   ```json
   {
     "env": {
       "WIKANTIK_BASE_URL": "https://wiki.example.com",
       "WIKANTIK_BRIEFING_CLUSTERS": "billing,onboarding",
       "WIKANTIK_BRIEFING_PINS": "CompanyGoals2026"
     },
     "hooks": {
       "UserPromptSubmit": [
         { "hooks": [ { "type": "command", "command": "$CLAUDE_PROJECT_DIR/clients/claude-code/briefing-hook.sh" } ] }
       ]
     }
   }
   ```

4. Start (or restart) a Claude Code session. The first prompt of the session
   fetches the briefing and injects it as context; subsequent prompts in the
   same session are no-ops.

## Env contract

| Variable | Required | Purpose |
|---|---|---|
| `WIKANTIK_BASE_URL` | Yes | Base URL of the Wikantik instance (e.g. `https://wiki.example.com`). Hook exits 0 silently if unset. |
| `WIKANTIK_BRIEFING_PINS` | No | Comma-separated list of page names to always include (`pins` query param). |
| `WIKANTIK_BRIEFING_CLUSTERS` | No | Comma-separated list of clusters to include (`clusters` query param). |
| `WIKANTIK_BRIEFING_BUDGET` | No | Token/size budget passed through to the `budget` query param. |
| `WIKANTIK_BASIC_AUTH` | No | `user:pass` for HTTP Basic auth, passed to `curl -u` when the wiki requires it. |

The user's first prompt text is forwarded as the `prompt` query param so the
briefing can be tailored (e.g. surfacing prompt-relevant sections/entities).

## How it degrades

The hook is designed to **never fail the user's prompt**. On any of the
following conditions it exits 0 with no output, and the prompt proceeds
exactly as if the hook weren't installed:

- `jq` is not installed (`command -v jq` fails).
- `WIKANTIK_BASE_URL` is unset or empty.
- stdin isn't valid JSON, or `session_id` is missing/empty.
- The state directory can't be created (e.g. read-only `$HOME`).
- A briefing was already injected for this `session_id` (state file present
  under `${XDG_CACHE_HOME:-$HOME/.cache}/wikantik-briefing/<session_id>.done`).
- The wiki is unreachable, times out (10s), or returns a non-2xx/curl error
  (`curl -fsS`) — e.g. DNS failure, connection refused, 404, 500.

Because the state file is written **before** the fetch, a briefing is
attempted at most once per session even if the wiki is down — it will not
retry on every subsequent prompt in that session.

## Resetting state

Claude Code session IDs are stable for the life of a session. To force a
re-fetch (e.g. after editing pins/clusters), delete the session's state file:

```bash
rm ~/.cache/wikantik-briefing/<session_id>.done
```

or clear all cached sessions:

```bash
rm -rf ~/.cache/wikantik-briefing
```

## Manual test

With a Wikantik instance reachable at `$WIKANTIK_BASE_URL`:

```bash
chmod +x clients/claude-code/briefing-hook.sh
echo '{"session_id":"test-1","prompt":"how does billing work"}' | \
  WIKANTIK_BASE_URL=http://localhost:8080 WIKANTIK_BRIEFING_PINS=Main ./clients/claude-code/briefing-hook.sh
```

Expected: markdown briefing on stdout. Running the same command again prints
nothing (state file already exists). Reset with `rm ~/.cache/wikantik-briefing/test-1.done`.

Failure path (unreachable host):

```bash
echo '{"session_id":"test-2","prompt":"x"}' | \
  WIKANTIK_BASE_URL=http://localhost:1 ./clients/claude-code/briefing-hook.sh
echo "exit=$?"   # exit=0, no stdout
```
