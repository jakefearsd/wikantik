## Wikantik context briefing

At the START of every new session or task, BEFORE any other work, call the
`get_briefing` tool on the wikantik knowledge MCP server with:
- `clusters`: ["<YOUR-CLUSTERS>"]
- `pins`: ["<YOUR-PINS>"]
- `prompt`: the user's first request, verbatim

Treat the returned briefing as authoritative standing context. For follow-up
questions during the session use `assemble_bundle`; fetch full pages with
`read_pages`. Do not call get_briefing again in the same session.
