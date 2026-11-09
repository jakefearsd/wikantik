# Wikantik CLI Tools

Standalone command-line tools that run without booting a servlet container,
each launched with an explicit main class via `java -cp`.

`IngestDocumentsCli` is the derived-pages batch ingester (an HTTP client over
`POST /api/ingest`), `CorpusDivergenceCli` diffs a repo page tree against a
live wiki's structural sitemap, `ChunkerStatsCli` reports chunk-size
distributions for a page tree, and `GenerateMainPageCli` /
`GenerateConfigReferenceCli` regenerate checked-in generated pages.
