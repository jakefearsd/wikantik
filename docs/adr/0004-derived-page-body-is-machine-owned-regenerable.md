# Derived pages: machine-owned regenerable body, human edits at their own risk

A derived page's body is owned by the extraction pipeline and is **reflowed (clobbered)**
whenever an improved extractor is run across the corpus; the retained source document is the
durable source-of-truth. Human curation that must survive reflow lives in the
**body-independent layers that already exist** — frontmatter, tags/cluster, Knowledge Graph
entity curation (`kg_*` tables), verification status, comments. Humans *may* still hand-edit
the extracted prose, but **at their own risk**: a reflow will overwrite it.

We deliberately add **no edit-protection, no locking, and no automatic three-way merge**.
Instead the `VersioningFileProvider` page history is the recovery path — prior extractions
and prior human edits stay visible and can be merged back by hand.

Rationale: this keeps "re-run improved extraction across the whole corpus" cheap and
lossless (a stated requirement), matches the codebase's already body-independent curation
model, and follows a "clear forceful actions, clear forceful consequences" principle over
hidden automation. Rejected: *promote-to-hand-authored on first edit* (loses regenerability
on exactly the pages humans cared most about) and *in-wiki three-way merge* (fragile when
the extraction base shifts, heavy conflict UX).
