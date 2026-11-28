# wikantik-work-mcp

The `/work-mcp` endpoint (`com.wikantik.work.mcp`): agents (own `wka_` tokens) and people's `work` API keys. Tools call `WorkService` and nothing else. Design: `docs/superpowers/specs/2026-11-15-work-management-design.md`; reference: `docs/developer/work/mcp.md` (read before changing auth, errors, logging or a tool's arguments). The rules in `wikantik-work/CLAUDE.md` apply.

**Invariants only: one line each, file ≤ 10 KB. Reference detail goes to `docs/developer/work/<area>.md`, never here.**

Invariants
- **Key-only.** `WorkMcpInitializer` uses `Builder.keyOnly(true)` + scope `WORK` (`KeyOnlyMcpAccessFilter`): `mcp.access.allowUnrestricted`/`allowedCidrs` are ignored. Never route `/work-mcp` through the config-driven `McpAccessFilter` (`KeyOnlyMcpAccessFilterTest`).
- **No user principal.** The verified credential is a request attribute (`AgentTokenAuth.ATTR_AGENT_REF` or `ApiKeyPrincipalRequest.ATTR_API_KEY_RECORD`); an agent token is verified by the work module and never sent to `ApiKeyService`.
- **One session, one credential:** another credential on a bound session gets 403 (`KeyOnlyMcpAccessFilterTest`, `WorkMcpSessionActorTest`, `WorkMcpIT.oneSessionServesOneKey`).
- **The actor is resolved per tool call** from that call's own request (`WorkMcpActors.extract`/`actorFor`), never from the session, with no fallback to a person, guest or default actor. A retired agent, or one with no sponsor or whose sponsor is gone, gets `AGENT_UNUSABLE`; a failed sponsor lookup is `Work is unavailable right now.` and changes nothing (`WorkMcpSessionActorTest`).
- **No store access:** no class here depends on `WorkStore`, any `Jdbc*`, `com.wikantik.jdbc` or `javax.sql`, and none calls `sweepExpiredClaims()`/`sweepStanding()` (`WorkMcpAccessArchTest`).
- **Names, never logins:** `WorkMcpJson`, `WorkMcpChanges`, `WorkMcpDeployments` render display names, agent and queue names; never a login, key id or token. Item text is untrusted Markdown source.
- **Allow-listed arguments:** every write tool calls `WorkMcpArgs.rejectOthers` first and refuses an undeclared argument by name; read tools ignore extras. Identity, `actor_kind`, agent ids, policy and review fields are never arguments. `update_item` refuses `sprint` ("A person plans a sprint.") before any service call.
- **Errors are text with a stable prefix**, mapped once in `WorkMcpTool.call` (`denied:`, conflict reasons, `<field>:`, `Not found.`, `This endpoint needs a work API key.`, unusable-agent text, `Work is unavailable right now.`). Unknown and hidden are both `Not found.` Keep the instructions file and tool descriptions in step.
- **Logging:** a successful write logs `MCP_WRITE`, a refused write `MCP_WORK_REFUSED tool= item= agent= kind=` on `SecurityLog` (`kind` never the message; fields stripped of control characters, capped at 64; `item` also loses whitespace and `=`). Reads log at DEBUG only. DB errors log class and SQLState, never the message (`WriteToolsAuditTest`, `WorkMcpLoggingTest`).
- **Off means 404:** `WikiEngine.workService()` null registers nothing; the `web.xml` listener stays after `WikiBootstrapServletContextListener`.
- No tool carries file bytes (attachments are listed with a REST path), no tool acknowledges a flag, no tool calls `intake()`; a reporter-only key reaches nothing here.

## Adding a tool

1. Subclass `WorkMcpTool`; logic in `WorkService`; `READ_ONLY` only for reads. 2. Register in `WorkMcpInitializer.assembleTools`. 3. Describe it in `src/main/resources/wikantik-work-mcp-instructions.txt`. A write tool also needs `WorkMcpLog.written` and a `WriteToolsAuditTest` case, and `McpAgents.md` lists it. Failing until done: `WorkMcpInstructionsDriftTest`, `WorkMcpInitializerTest.assembledToolNames`, `WorkMcpIT.EXPECTED_TOOLS` (derive the count from `assembleTools`; do not copy it). The admin MCP's `EXPECTED_TOOLS` is unaffected.
