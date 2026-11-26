# wikantik-agent-reports

Append-only store of agent field reports (`com.wikantik.agentreports.*`): domain records, `AgentReportValidator`, `JdbcAgentReportStore`, `AgentReportService`. Only this module touches the `agent_report*` tables (V098); there is no update or delete path.
Depends on neither `wikantik-api` nor `wikantik-main` — only `wikantik-jdbc` and log4j-api; the MCP tools that file and list reports live in `wikantik-admin-mcp`.
Built extraction-ready: it carries no wiki types, so it can move to its own repository unchanged.
