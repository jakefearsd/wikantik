# Clusters and Hubs

This guide is for wiki authors and editors. It explains how pages are grouped into clusters, how a hub page declares a cluster, how to put a page in one or more clusters, and what the cluster badge on a page means.

## How a cluster comes to exist

A cluster exists when exactly one page declares it. That page is the cluster's **hub**: it has `type: hub` and a scalar `cluster:` value.

```yaml
---
type: hub
cluster: cooking
title: Cooking
summary: Recipes, techniques and equipment notes.
---
```

There is no separate registry of clusters. If you delete or retype the hub, the cluster stops being declared and its member pages show "cluster not yet defined" to editors (see [Read the cluster badge](#read-the-cluster-badge)).

A cluster path is lowercase kebab-case segments (`cooking`, `food-science`). Anything else draws a warning when you save.

## Put a page in a cluster

Add `cluster:` to the page's frontmatter. The value is the path the hub declares.

```yaml
---
type: article
cluster: cooking
---
```

A non-hub page can belong to several clusters by using a list. The first entry is the **primary** cluster.

```yaml
---
type: article
cluster: [cooking, food-science]
---
```

The primary cluster decides where the page is placed: its breadcrumbs, the topic it is filed under in search-engine metadata, its position in the sidebar tree and sitemap, and the context added when the page is indexed for semantic search. The other clusters add membership only: the page is listed under each of those hubs and matches filters for each of them.

A hub must keep `cluster:` as a single value. Saving a hub with a list is rejected as an error. See [Frontmatter](Frontmatter.md) for the full field reference.

## Create a sub-cluster

Write the cluster as `parent/child`:

```yaml
cluster: cooking/baking
```

Sub-clusters go one level deep and no further. A sub-cluster needs its own hub declaring `cluster: cooking/baking`. Pages in a sub-cluster are also members of the parent for listing and filtering, so a page that names both `cooking` and `cooking/baking` is counted once.

## Read the cluster badge

Everyone sees the cluster path as a plain tag on a page. Editors (people who can edit that page) also see its status. The component is `wikantik-frontend/src/components/ClusterStatus.jsx`.

| What you see | What it means |
|---|---|
| `HUB · declares cooking · 12 pages` | This page is the hub for that cluster. A `parent` entry appears for a sub-cluster. The count is the number of member pages. |
| A tag that links to a page | The page is in that cluster and the link goes to the hub that declares it. |
| A tag plus "cluster not yet defined" | The page names a cluster that no hub declares yet. Create the hub, or fix the cluster name. |
| "unclustered" | The page names no cluster, so it belongs to no hub. |
| Extra, lighter tags after the first | The page's other memberships, shown only when it has two or more. Each links to its hub, or shows "cluster not yet defined". |

## Save rules

The wiki warns about most cluster problems and still saves the page. Warnings are collected on the admin drift report for an administrator to work through.

Two hubs declaring the same cluster is the one case that can block a save. The check is controlled by `wikantik.cluster_declaration.enforcement.enabled`, which ships **off** (`false`) in `ini/wikantik.properties`. With the default, a duplicate declaration saves and is reported as drift. When an administrator turns the setting on, saving the second hub fails with a `cluster.duplicate_declaration` error until you pick a different cluster path.

## Rename a cluster

Renaming a cluster rewrites the `cluster:` value on the hub and every member page, so it is an administrator task. Ask an administrator to run it from `POST /admin/clusters/rename` (or the `rename_cluster` admin MCP tool). Without `confirm=true` the call returns a plan and changes nothing. If another hub already declares the target path, the rename is refused. Page names, page IDs and URLs do not change.

## See also

- [Frontmatter](Frontmatter.md): the `type`, `cluster` and related fields.
- [Obsidian import and export](ObsidianImportExport.md): importing a vault can create clusters and hubs from folders.
