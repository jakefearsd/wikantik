# Wikantik Entity-Extractor CLI

Standalone command-line tools that run against a Wikantik database without
booting a servlet container. `BootstrapExtractionCli` (the packaged
executable's main class) drives the save-time entity-extractor pipeline
offline, so a multi-hour corpus-wide extraction can continue while the main
Tomcat instance is stopped or rebuilt for local development.

The module also hosts several related batch tools: `IngestDocumentsCli` (the
derived-pages batch ingester, an HTTP client over `POST /api/ingest`),
`CorpusDivergenceCli` (diffs a repo page tree against a live wiki's
structural sitemap), `KgPolicyCli`, and `GenerateMainPageCli`.
