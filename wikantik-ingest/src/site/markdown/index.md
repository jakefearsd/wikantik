# Wikantik Document Ingest

Pure document-extraction layer for derived pages: `SourceExtractor` and its
Apache Tika-based implementation, `TikaSourceExtractor`, convert uploaded
documents (via `tika-core` + `tika-parsers-standard-package`) to XHTML and
then to markdown with flexmark-html2md.

This module depends only on Tika and flexmark, deliberately isolating the
heavy PDFBox/POI parser dependencies from `wikantik-main`, which depends on
it. The wiki-coupled side of ingestion — reflow, staleness tracking, and the
`derived_from` frontmatter convention — lives in `com.wikantik.derived` in
`wikantik-main`, not here.
