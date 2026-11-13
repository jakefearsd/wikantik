# wikantik-ingest

Module notes, loaded when working under this directory (moved from the root CLAUDE.md).

- **wikantik-ingest**: Tika + flexmark-html2md extraction for **derived pages**, isolating PDFBox/POI from wikantik-main. A page is derived iff its frontmatter has `derived_from`; its body is machine-owned (ADR-0004). Wiki side `com.wikantik.derived.*`; surfaces `POST /api/ingest`, `/admin/derived/{reflow,status}`.
