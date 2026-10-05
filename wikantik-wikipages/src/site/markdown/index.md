# Wikantik Wiki Pages

The English system-page set shipped with Wikantik, in the `en` module. The module is
on the WAR's classpath so `DefaultSystemPageRegistry` can discover the system page names:
it treats every `*.md` file next to `About.md` as a system page (write-protected over MCP,
left out of the search index, sitemap and recent-articles lists).

The files are not copied into a page store on first deployment. A new install serves its
pages from `docs/wikantik-pages/` (local deployment) or the copy the Docker image makes of it.
Keep a page in both places when you change one of these.
