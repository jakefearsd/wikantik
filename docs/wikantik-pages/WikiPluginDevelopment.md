---
canonical_id: 01KQ0P44Z46M6NDJJNCJVF2B8N
title: Wiki Plugin Development
type: article
cluster: wikantik-development
status: active
date: '2026-04-26'
summary: How to develop wiki plugins — extension points, plugin architectures, the
  patterns that produce maintainable plugins vs. fragile ones.
tags:
- wiki
- plugins
- extensions
- wikantik-development
related:
- WikiPerformanceTuning
- WikiContentManagementWorkflow
- CleanCodePrinciples
---
# Wiki Plugin Development

Wikis are extensible. Plugins add features the core doesn't provide: custom rendering, integrations, workflows, analytics. Plugin systems vary by wiki platform.

This page covers the patterns for developing plugins that work and stay maintainable.

## Plugin types

### Renderer plugins

Convert custom syntax to HTML. `[{Calendar}]` becomes a calendar widget.

### Filter plugins

Pre/post-process content. Strip HTML; apply transformations; insert metadata.

### Listener / hook plugins

React to events: page saved, user logged in, comment posted.

### Provider plugins

Pluggable storage: page provider, attachment provider, user provider, search provider.

### UI plugins

Custom UI elements: toolbar buttons, custom forms, dashboard widgets.

### Integration plugins

Connect to external systems: Slack notifications, JIRA links, GitHub commits.

## Wiki platform plugin systems

### MediaWiki extensions

Hook-based. Each extension registers for hooks and provides handlers.

Mature; many available; well-documented.

### Confluence apps

Atlassian Connect / Forge. Cloud-based; more constrained model than self-hosted plugins.

### DokuWiki plugins

Action plugins, syntax plugins, helper plugins. Object-oriented.

### Wikantik plugins

Multiple extension mechanisms (per CLAUDE.md):
- **Plugins**: dynamic content via `[{Plugin}]` syntax
- **Filters**: content pre/post-processing
- **Providers**: storage backends
- **Modules**: larger feature additions
- **Templates**: UI customization

## Design principles

### Single responsibility

Each plugin does one thing. Plugins that try to do many things become brittle.

### Configuration

Plugins should be configurable. Hard-coded behavior limits reuse.

```yaml
plugin_name:
  enabled: true
  some_setting: value
```

### Defaults that work

Out of the box, plugin should work without configuration. Custom config for advanced needs.

### Failure mode

What happens when the plugin fails? Wiki should continue; just the plugin's portion broken.

A plugin that takes the wiki down on error is a bad plugin.

### Minimal surface area

Plugin uses only the platform's stable API. Doesn't reach into internals; doesn't depend on undocumented behavior.

## Specific patterns

### Lifecycle

Plugins typically have:
- **Install**: register; initialize storage
- **Init**: set up state at wiki startup
- **Active**: process events
- **Shutdown**: clean up
- **Uninstall**: remove storage; deregister

Handle each appropriately.

### Configuration UI

Some plugins have configuration UIs. The platform usually provides framework for this.

For complex config, separate page; simple config can be inline.

### Documentation

Each plugin needs:
- Purpose
- Installation steps
- Configuration
- Usage examples
- Troubleshooting

Without this, users can't adopt.

### Versioning

Plugins evolve. Semantic versioning helps consumers know about breaking changes.

For widely-distributed plugins, version compatibility matrices.

### Testing

Unit tests for plugin logic; integration tests against the wiki platform.

For complex plugins, automated testing is essential. For simple, smoke tests may suffice.

## Performance considerations

### Renderer plugins on hot pages

Plugins that render on every page view affect performance. See [WikiPerformanceTuning](WikiPerformanceTuning).

Cache renderer output where possible.

### Listener plugins on common events

Hooks that fire on every page save: keep them fast. Slow hooks slow saves.

### External calls

Plugin makes API calls to external service: each call adds latency.

For non-critical: async (fire and forget).
For required: timeout aggressively; handle failure.

### Database queries

Plugins that query the database: indexed queries; pooling; pagination.

Most plugin slowness comes from one of these.

## Maintenance

### Plugin lifecycle

Plugins age. Wiki platform updates; plugin may break. Maintain or deprecate.

### Compatibility

For platform upgrades: test plugins. Update for new APIs.

### Decommission

When a plugin is no longer needed, remove cleanly. Deactivate; remove; verify wiki still works.

## Plugin distribution

### Bundled with wiki

Some plugins ship with the wiki. Pre-installed.

### Marketplace / repository

Public plugins available for installation. Atlassian Marketplace for Confluence; Extension:Distribution for MediaWiki.

### Internal repository

For organization-specific plugins, internal distribution.

### Source-installed

Clone from source; build; install. Common for custom plugins.

## Specific Wikantik plugin examples

Per the codebase context:
- **Dynamic content via `[{Plugin}]` syntax**: e.g., a `[{TableOfContents}]` plugin auto-generates a TOC
- **Filters**: e.g., `StructuralSpinePageFilter` enforces structural-spine schema at save time
- **Providers**: e.g., `FileSystemProvider` for page storage
- **Custom rendering**: Markdown via Flexmark with custom extensions

The Wikantik plugin pattern uses standard interfaces; implementations registered via configuration or auto-discovery.

## Common failure patterns

### Plugin takes down the wiki

Unhandled exceptions in hooks. Catch; log; continue.

### Plugin tightly coupled to wiki internals

Reaches into internals not in the public API. Breaks on platform updates.

### No documentation

Users can't install or configure.

### Performance not considered

Plugin is fast in isolation; slow at scale.

### No upgrade path

New version incompatible with old; users can't upgrade gracefully.

### Configuration sprawl

Plugin requires extensive configuration; nobody understands the options.

## A reasonable approach

For new plugin development:

1. Identify the specific need
2. Use stable platform APIs
3. Defaults that work; configuration for tuning
4. Handle failure gracefully
5. Performance-aware
6. Document clearly
7. Test (unit + integration)
8. Version carefully

## Further Reading

- [WikiPerformanceTuning](WikiPerformanceTuning) — Plugin overhead
- [WikiContentManagementWorkflow](WikiContentManagementWorkflow) — Editorial integration
- [CleanCodePrinciples](CleanCodePrinciples) — General code quality
