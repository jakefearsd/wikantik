---
canonical_id: 01KQ0P44Z1AZESNHTQDJANTSB1
title: Wiki Migration Strategies
type: article
cluster: wikantik-development
status: active
date: '2026-04-26'
summary: How to migrate from one wiki platform to another — content extraction, format
  conversion, link preservation, and the patterns that prevent the worst case (broken
  wiki on the new platform).
tags:
- wiki
- migration
- platform-change
- wikantik-development
related:
- WikiBackupAndRestore
- LegacyCodeModernization
- CloudMigrationStrategies
---
# Wiki Migration Strategies

Migrating wikis is rarely fun. Source wiki has its quirks; target wiki has its own. Pages have history, links, attachments, formatting that may not translate cleanly.

This page covers the patterns for surviving wiki migrations.

## Common migration scenarios

### Platform change

MediaWiki → Confluence; Confluence → Notion; Markdown-in-git → SaaS wiki.

The hardest. Different content models, different feature sets.

### Self-hosted to SaaS

On-premises Confluence to Confluence Cloud; self-hosted MediaWiki to a managed service.

Less hard; same platform but different deployment.

### SaaS to self-hosted

The reverse — usually data exodus from a vendor.

Often time-pressured (vendor changes terms; price increase; outage). Plan for it.

### Consolidation

Merging multiple wikis into one. Multiple sources; one target.

Often involves deduplication, conflict resolution, link rewriting.

## What migrates poorly

### Custom formatting

Wiki-specific markup that doesn't have an equivalent in the target. Tables, callouts, embedded macros.

### Macros / plugins

Source wiki has a custom macro that displays a calendar. Target wiki doesn't. Page now broken.

### Embedded content

YouTube videos, Twitter embeds, etc. Embedding methods vary; may break.

### Page hierarchy

Some wikis have explicit hierarchy (Confluence); some don't. Mapping isn't always clean.

### Attachments

Inline references to attached files. URL structure changes; references break.

### Permissions

Source has fine-grained permissions; target's model is different. Hard to translate exactly.

### History

Edit history may not migrate at all. Or migrates as a single "imported" entry.

For most platforms, accepting some history loss is realistic.

## The migration process

### Phase 1: discovery

What's in the source?
- Page count
- Attachment count and size
- Custom formatting / macros used
- Permissions
- Active vs. dead content

Most migrations have surprises. Discovery surfaces them.

### Phase 2: pilot

Migrate a small subset (10-50 pages). See what breaks.

- Formatting issues
- Broken links
- Missing attachments
- Permission gaps

Iterate on the migration tool until the pilot is acceptable.

### Phase 3: full migration

Run the migration at scale. Probably overnight or weekend. Monitor for errors.

### Phase 4: validation

Spot-check pages. Use analytics to verify high-traffic pages.

### Phase 5: link rewriting

Old wiki URLs likely don't match new ones. Either:
- Rewrite links during migration
- Set up redirects from old URLs
- Both

Without this, links throughout the new wiki break.

### Phase 6: parallel run

For a period, both old and new wikis available. Users transition. Old wiki marked read-only; eventually decommissioned.

### Phase 7: decommission

Old wiki goes away. Final backup; archive; remove.

## Tools

### Wiki-specific tools

Many platforms ship migration tools: Confluence has importers; MediaWiki has dump tools.

Check what's available for the source-target combination.

### Pandoc

Converts between document formats. For markdown, MediaWiki, AsciiDoc, etc., pandoc handles much of the format translation.

### Custom scripts

For complex migrations, custom code reads source; writes target. Time investment but flexibility.

### SaaS migration services

Some companies specialize in wiki migrations. Worth it for large complex migrations; overkill for small.

## Patterns for cleaner migrations

### Clean up before migrating

Migrate clean content; don't carry forward dead pages, broken links, deprecated content.

Pre-migration audit: delete what's not worth migrating.

### Document mapping

Source format → target format mappings. Where the source has X, what's the equivalent target?

Spot the gaps where there's no equivalent.

### Test pages explicitly

Before migrating production content, test pages with known features:
- Tables
- Code blocks
- Embedded media
- Various formatting

See what works.

### Preserve URLs where possible

Keep page slugs the same. External bookmarks survive.

If URLs must change, redirects are essential.

### Communicate

Users need to know:
- When the migration is happening
- What may change
- How to find content in the new system
- Who to ask for help

## The "rewrite vs. migrate" decision

Sometimes pages are bad enough that rewriting is better than migrating. The "garbage in, garbage out" problem — bad source content stays bad in the target.

For high-traffic pages: consider rewriting during migration.

## Common failure patterns

- **Migrating without discovery.** Surprises during.
- **No pilot.** Full migration breaks; nothing tested.
- **No link rewriting.** New wiki has broken internal links.
- **No redirects.** External bookmarks break.
- **Migrating dead content.** Effort wasted.
- **No parallel run.** Users get cut off cold.
- **No communication.** Users find out via broken bookmarks.

## A reasonable approach

For typical wiki migrations:

1. Discovery (1-4 weeks)
2. Pilot (small subset; iterate)
3. Pre-migration cleanup (remove dead content)
4. Full migration (off-hours)
5. Link rewriting + redirects
6. Parallel run (1-3 months)
7. Decommission

Plan for the timeline. Migrations always take longer than estimated.

## Further Reading

- [WikiBackupAndRestore](WikiBackupAndRestore) — Backup precedes migration
- [LegacyCodeModernization](LegacyCodeModernization) — Adjacent practice
- [CloudMigrationStrategies](CloudMigrationStrategies) — Cloud parallel
