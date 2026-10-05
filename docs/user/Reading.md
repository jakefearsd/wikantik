# Reading a Page

This guide is for people who read the wiki. It explains what you see on a page and around it: the header, the table of contents, the trail of pages you visited, link previews, the lists of related pages, history and comparison of versions, the banner on synced pages, the two graph views, dark mode and the ways to export content.

## The page header

Under the breadcrumb trail, a line of facts about the page shows, from left to right:

- The author and the last-modified date.
- The version number, for example `v7`.
- The page's cluster (its topic area) as a chip. See [ClustersAndHubs.md](ClustersAndHubs.md).
- The reading time, for example "4 min read".
- A trust badge when the page has one: **Verified** (hover to see when), **Provisional** or **Stale**. It comes from the `confidence` field described in [Frontmatter.md](Frontmatter.md).

If you can edit the page, the cluster chip shows more. A hub page reads "HUB · declares `cluster` · N pages". A page with no cluster is marked **unclustered**. A page whose cluster no hub declares yet is marked **cluster not yet defined**. A page in several clusters shows the extra ones as smaller chips. Readers who cannot edit see only the plain cluster chip.

Buttons at the right of the header depend on your permissions: **Edit**, **Rename** and **Delete**. On a hub page, signed-in readers also get **Export this cluster**. When a page has comment threads, a **Comments (N)** button opens the discussion drawer; see [CommentsAndMentions.md](CommentsAndMentions.md).

Two collapsed sections sit below the header. **Properties** lists the page's frontmatter. **Change Notes** lists its versions.

## Find your way around the page

### Breadcrumbs: the pages you just visited

The line at the top is a trail of the last three different pages you opened in this browser tab, oldest first, ending with the current page. Earlier entries are links. It is a history, not the page's position in a topic tree: it is kept per tab, works when you are signed out, and is cleared when you close the tab (`usePageTrail.js`).

### Table of contents

A page with at least three `##` or `###` headings shows an **On this page** list at the right. Click a heading to jump to it; the entry for the section you are reading is highlighted as you scroll. Hover any heading to reveal an anchor link you can copy.

### The sidebar

The left sidebar has a **Search…** button (see [Search.md](Search.md)); **Navigation** links (Main page, About, News, Recent Changes); **Wiki Tools** links (Page Index, System Info, Page Graph and, when enabled, Knowledge Graph); **Recently Modified** with the five latest changes ("Show all N" for more); and a collapsible tree of every cluster with its pages. When you are signed in it also holds your own panel; see [PersonalZone.md](PersonalZone.md). The sidebar can be collapsed from the command palette ("Toggle sidebar").

## Preview a link

Hover a link to another wiki page for about 0.4 seconds, or move keyboard focus to it, and a card appears with the page's title, type, cluster and its summary (or, without one, the start of its text). For a link to a section, the excerpt starts at that section. A link to a page that does not exist shows "Not created yet". The card is built from `GET /api/pages/{name}/preview` and respects page permissions.

## Related pages

Two lists under the article help you move sideways:

- **Referenced by** lists the pages that link to this one. It is built from real links in page bodies and is hidden when nothing links here.
- **Similar pages** lists up to five pages that mention similar subjects. It is built from the entities the Knowledge Graph extracted from the text, so it is hidden when the Knowledge Graph has no data for the page.

## See the page history and compare versions

Click **Change Notes** under the page header to list every version with its number, author, date and change note. If you can edit the page, each older version has a **Restore** link, which opens the editor with that version's text (see [Editing.md](Editing.md#edit-conflicts-and-old-versions)). With two or more versions, **Compare versions →** opens the comparison page, also reachable from the command palette as **Page history** (`/diff/PageName`).

On the comparison page, choose a **From version** and a **To version**. The dropdowns show each version's author and change note. The difference between them is rendered below. Choosing the same version twice shows "Select two different versions to see changes."

## Synced pages

A page whose text is generated from an uploaded document or an external source is marked by a banner under the header: "Synced from SOURCE · last synced DATE · via connector NAME · body is machine-managed". If the connector was removed the banner adds "source no longer syncing". The same pages carry a **↯** badge in the sidebar and in search results. The text of such a page is rewritten on every sync, so edit its metadata rather than its body. See [Frontmatter.md](Frontmatter.md#derived-page-provenance).

## See how a page connects to the rest of the wiki

The wiki has two graph views. They are different things and answer different questions.

| | Page Graph | Knowledge Graph |
|--|-----------|-----------------|
| Nodes | Pages | Entities such as people, organizations, technologies and concepts, extracted from page text |
| Edges | Real links from one page to another | Typed relations between entities |
| Opened from | **Page Graph** in the sidebar, route `/page-graph` | **Knowledge Graph** in the sidebar, route `/knowledge-graph` |
| Always available | Yes | Only when the server reports `capabilities.knowledgeGraph`, which follows `wikantik.knowledge.enabled` (default `true`) |

**Page Graph.** The sidebar link opens the graph centred on the page you are reading (`/page-graph?focus=PageName`). Use **Fit to view** and **Refresh** in the toolbar, and the filter panel to limit the graph by cluster, tag, type, status or search text; each active filter shows as a chip you can remove. Pages you may not view are shown as restricted placeholders.

**Knowledge Graph.** The viewer has **Tier** and **Edges** selectors in its toolbar, and clicking a node opens a drawer with its type, provenance, status, tier and cluster. If the link is missing from the sidebar, your wiki has the Knowledge Graph turned off.

## Dark mode

Click the sun or moon button beside your name at the top of the sidebar to switch between light and dark. The first time, the wiki follows your operating system's setting; after you click, your choice is remembered in this browser.

## Export content

### Export pages as an Obsidian vault

Click **Export to Obsidian…** in the sidebar (signed-in users), or **Export this cluster** on a hub page, to download a `.zip` you can open as an Obsidian vault. The dialog lets you choose Clusters, Tags, Type and Status, how many hops of linked pages to add (0, 1 or 2), and whether links to pages outside the export stay as `[[links]]` or become links to the wiki. A live line such as "12 pages · up to 3 attachments · ~0.4 MB · 2 links unresolved" updates as you choose, and **Download vault (.zip)** stays disabled when nothing matches or the selection is larger than the limit (`wikantik.export.maxPages`, default 2000). Only pages you can view are included. If your account lacks the `export` permission the dialog says "Export is disabled for your account." See [ObsidianImportExport.md](ObsidianImportExport.md) for the full workflow, including import.

### Get the raw text of one page

Append `?format=md` or `?format=json` to a page address, for example `/wiki/PageName?format=md`, to get the page's Markdown or a JSON form without the web interface. It follows the same view permission as the page, and a restricted page answers 404.

## Source files

- `wikantik-frontend/src/components/PageView.jsx`, `PageMeta.jsx`, `ClusterStatus.jsx`, `MetadataPanel.jsx`
- `wikantik-frontend/src/components/Breadcrumbs.jsx`, `TableOfContents.jsx`, `Sidebar.jsx`
- `wikantik-frontend/src/hooks/useLinkPreview.js`, `usePageTrail.js`, `useDarkMode.js`
- `wikantik-frontend/src/components/BacklinksPanel.jsx`, `SimilarPagesPanel.jsx`, `ChangeNotesPanel.jsx`, `DiffViewer.jsx`
- `wikantik-frontend/src/components/DerivedProvenanceBanner.jsx`, `ExportDialog.jsx`
- `wikantik-frontend/src/components/pagegraph/`, `kgraph/`
- `wikantik-rest/src/main/java/com/wikantik/rest/CapabilitiesResource.java`, `ExportResource.java`, `WikiPageFormatFilter.java`
