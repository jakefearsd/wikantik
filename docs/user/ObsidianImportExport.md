# Obsidian Import and Export

This guide is for people who keep notes in Obsidian and want to move them into the wiki, or take wiki pages out as an Obsidian vault. It covers the sidebar dialogs, what each direction converts, who is allowed to use them, and the size limits. The same operations are available as REST endpoints for scripts.

## Export pages to a vault

You need to be signed in and hold the `export` permission. If you lack it, the dialog says "Export is disabled for your account" and the endpoint returns 403.

1. Open **Export to Obsidian…** in your sidebar (the Personal Zone), or, on a cluster's hub page, the **Export this cluster** button, which preselects that cluster.
2. Choose what to include. You can combine any of these:
   - **Clusters**, with **Include sub-clusters** on by default.
   - **Tags**, **Type** and **Status**.
   - **Hops from the selection**: also include pages the selection links to, 0 to 2 links away.
3. Choose **Unresolved links**: **Keep as [[links]]** leaves links to pages outside the export as wikilinks, **Link to the wiki** turns them into URLs back to the live wiki.
4. Check the size estimate, then choose **Download vault (.zip)**.

Only pages you can view are exported. A page you cannot view is left out, and so is anything reachable only through it.

### What the zip contains

- One Markdown file per page, with your frontmatter kept and extended: `wikantik_url` and `wikantik_version` are added, the page title is added to `aliases`, and `related:` entries become wikilinks.
- Links between exported pages become `[[wikilinks]]`, and images and attachments become `![[embeds]]`, with the attachment files included.
- `cite://` citations become footnotes. Wiki plugin markup that Obsidian cannot show is converted on a best-effort basis. If a page cannot be converted, its raw Markdown is written instead.
- A manifest and a README describing the export.

### Script it

```bash
curl -u "$LOGIN:$PASSWORD" -o vault.zip \
  "http://localhost:8080/api/export?cluster=cooking&subclusters=true&hops=1&unresolved=url"
```

Query parameters: `cluster` and `tag` (repeatable), `subclusters`, `type`, `status`, `hops` (0 to 2) and `unresolved` (`keep` or `url`). `GET /api/export/preview` takes the same parameters and returns an estimate without building the zip. `GET /api/export/options` lists the clusters and tags the dialog offers.

### Export limits

| Limit | Default | Key |
|---|---|---|
| Pages in one export | 2000. A larger selection is refused with 413 and the real count; it is never truncated. | `wikantik.export.maxPages` |
| Downloads running at once, wiki-wide | 2. A third request gets 429; try again shortly. | `wikantik.export.maxConcurrent` |

## Import a vault

You need to be signed in and hold the `createPages` permission. The import is a dry run first, so you see the result before anything is written.

1. Zip your vault folder.
2. Open **Import Obsidian vault…** in your sidebar. It only appears if you can create pages.
3. Choose the zip, and choose how clusters are assigned:
   - **Folders become clusters**: each vault folder becomes a cluster.
   - **Put everything in cluster…**: every note joins one existing cluster, which must already have a hub.
   - **No clusters**: notes get no `cluster:`.
4. Review the plan: the totals, a per-page table, the attachments, and grouped warnings.
5. Choose **Import N pages**. The dialog shows progress. You can close it, and reopening it shows the running job.

The plan carries a hash. When you apply, the server plans the vault again and refuses with 409 if the vault or the wiki changed since your plan, so you never apply something you did not review. Only the user who started a job (or an administrator) can read its status.

### What is converted

**Page names.** Each note becomes a page named after its file. When two notes share a name, the later one gets the folder in brackets or a number. A name that is not legal as a wiki page name is adjusted.

**Existing pages are never overwritten.** A note whose page name already exists is reported as skipped. Reserved names are skipped too.

**Links and embeds.** `[[Note]]`, `[[Note#Heading|alias]]` and `![[embed]]` are retargeted to the new page names, keeping the heading and the alias. Markdown links to notes become `[[wikilinks]]`. Links to attachments become `Page/file` references. Links that match no note are kept and listed as warnings. Code blocks and inline code are not touched.

**Removed.** Obsidian `%% comments %%` and `^block-id` markers are stripped, and the number removed is reported in the warnings.

**Frontmatter.**
- `title` is added (the original file name) when the page name differs from it.
- `tags` are merged with inline `#tags`, lowercased, with `/` turned into `-`.
- `alias` and `aliases` are merged into `aliases`, and the original file name is added when the page name differs.
- `cluster` is set from the option you chose, replacing any `cluster:` in the note.
- `type` is kept if it is a wiki page type. Otherwise it is dropped with a warning. A note's `type: hub` is downgraded to `article` unless it is the folder's hub.
- `canonical_id`, `wikantik_url`, `wikantik_version`, `verified_at`, `verified_by` and other read-only fields are dropped.
- Malformed YAML is kept as a code block at the top of the page, with a warning.

**Hubs.** With **Folders become clusters**, a folder note (a note named like its folder) becomes that cluster's hub. A folder without one gets a generated hub page. If a folder's cluster already has a hub declared in the wiki, the import joins that hub and creates no new one. See [Clusters and hubs](ClustersAndHubs.md).

**Attachments.** Files that notes reference are imported as attachments. Unreferenced files are skipped. A file is blocked if its type is on the blocked-upload list, exceeds the attachment size limit, or the upload policy refuses its type.

### Script it

```bash
# 1. Dry run: returns the plan, including planHash
curl -u "$LOGIN:$PASSWORD" -F file=@vault.zip -F clusterMode=folders \
  http://localhost:8080/api/import/obsidian/plan

# 2. Apply: returns 202 and a jobId
curl -u "$LOGIN:$PASSWORD" -F file=@vault.zip -F clusterMode=folders -F planHash=<hash> \
  http://localhost:8080/api/import/obsidian/apply

# 3. Poll
curl -u "$LOGIN:$PASSWORD" http://localhost:8080/api/import/obsidian/jobs/<jobId>
curl -u "$LOGIN:$PASSWORD" http://localhost:8080/api/import/obsidian/jobs/current
```

For `clusterMode=fixed`, also send `cluster=<path>`. The default mode is `folders`. Job status is held in memory, so a job's record is lost if the wiki restarts.

### Import limits

| Limit | Default | Key |
|---|---|---|
| Upload size | 100 MiB (104857600 bytes) | `wikantik.import.maxUploadBytes` |
| Uncompressed size | 500 MiB (524288000 bytes) | `wikantik.import.maxUncompressedBytes` |
| Zip entries | 20000 | `wikantik.import.maxEntries` |
| Notes | 2000 | `wikantik.import.maxPages` |
| One page body | 262144 bytes | `wikantik.api.maxPageBytes` |
| Imports running at once | 1 | `wikantik.import.maxConcurrent` |
| Plans computed at once | 2 | `wikantik.import.maxConcurrentPlans` |
| Total note text buffered while planning | 128 MiB (134217728 bytes); refused with 413 | `wikantik.import.maxNoteTextBytes` |

Exceeding a limit is refused with a message naming the key. Ask an administrator to change a limit.

## See also

- [Clusters and hubs](ClustersAndHubs.md)
- [Frontmatter](Frontmatter.md)
- [Citations](Citations.md)
