---
status: active
date: '2026-04-26'
title: Wiki Plugin Development
related:
- WikiPerformanceTuning
- WikiContentManagementWorkflow
- CleanCodePrinciples
canonical_id: 01KQ0P44Z46M6NDJJNCJVF2B8N
cluster: wikantik-development
type: article
tags:
- wiki
- plugins
- extensions
- wikantik-development
summary: How to develop wiki plugins — extension points, plugin architectures, the
  patterns that produce maintainable plugins vs. fragile ones.
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

Wikantik keeps JSPWiki's extension model, minus the JSP-era pieces:

- **Plugins**: Java classes implementing `com.wikantik.api.plugin.Plugin`, invoked from a page as `[{PluginName param=value}]`. See "Writing a Wikantik plugin" below.
- **Page filters**: `com.wikantik.api.filters.PageFilter` hooks (`initialize`, `preTranslate`, `postTranslate`, `preSave`, `postSave`, `destroy`).
- **Providers**: `PageProvider` and `AttachmentProvider` in `com.wikantik.api.providers`, selected by `wikantik.pageProvider` and `wikantik.attachment.provider`. `SearchProvider` lives in `wikantik-main`.
- **Events**: listeners on the `wikantik-event` bus (page saved, user logged in, and so on).
- **MCP tools**: agent-facing tools are `McpTool` implementations; see [WritingANewMcpTool](WritingANewMcpTool).
- **Modules**: larger features are Maven modules wired into the WAR by `wikantik-war`.

There is no template layer to customize any more: the JSP templates are gone and the UI is a React SPA.

## Writing a Wikantik plugin

A plugin is one class. The page syntax is `[{HelloPlugin name=Ada}]`; the Markdown pipeline rewrites `[{Plugin}]` to `[{Plugin}]()` so Flexmark parses it as a link node, and `PluginManager` then runs the class.

```java
package com.example.wiki;

import com.wikantik.api.core.Context;
import com.wikantik.api.exceptions.PluginException;
import com.wikantik.api.plugin.Plugin;
import com.wikantik.util.TextUtil;

import java.util.Map;

public class HelloPlugin implements Plugin {
    @Override
    public String execute( final Context context, final Map< String, String > params ) throws PluginException {
        final String who = params.getOrDefault( "name", "world" );
        // The return value is inserted into the page as HTML, so escape anything user-supplied.
        return "<span>Hello, " + TextUtil.escapeHtml( who ) + "</span>";
    }
}
```

- **Parameters.** `params` holds the `key=value` pairs from the page. Special keys are `_body` (the text after an empty line), `_cmdline`, `_bounds` and `debug` (constants on `PluginManager`).
- **Discovery.** Use a fully qualified class name in the page, or add your package to `wikantik.plugin.searchPath` (comma-separated; `com.wikantik.plugin` is always searched last, so you can override a built-in). Jars outside `WEB-INF/lib` go in `wikantik.plugin.externalJars`. To give a plugin a short alias, declare it in a `wikantik_module.xml` inside your jar, as the core plugins are in `wikantik-main/src/main/resources/ini/wikantik_module.xml`.
- **Initialization.** Implement `InitializablePlugin.initialize( Engine )` for one-time setup. It runs once before the first `execute`: at startup if the plugin has a `wikantik_module.xml` declaration, otherwise on first use.
- **Failures.** An exception is logged at WARN and the reader sees an inline error message in place of the plugin; the rest of the page still renders.
- **Caching.** Every plugin execution marks the render viewer-sensitive (`Context.VAR_VIEWER_SENSITIVE`), which keeps that render out of the shared, principal-less render caches. A plugin-heavy page therefore costs more to serve, so keep plugin code fast.
- **Security.** A plugin runs with the viewing user's `Context`; check permissions before doing anything privileged. `JDBCPlugin` shows the other end of the scale: it runs page-authored SQL and ships disabled behind `wikantik.plugin.jdbc.enabled`.

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

For complex plugins, automated testing is essential. For simple, smoke tests may suffice. In Wikantik, plugin tests in `wikantik-main` build an engine with `TestEngine`; see `CurrentTimePluginTest` and `DefaultPluginManagerTest` under `wikantik-main/src/test/java/com/wikantik/plugin/`, and run one with `mvn test -pl wikantik-main -Dtest=CurrentTimePluginTest`.

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

- **Built-in plugins** in `com.wikantik.plugin`: `InsertPage`, `Search`, `Image`, `IndexPlugin`, `RecentChangesPlugin`, `RecentArticles`, `ReferringPagesPlugin`, `ReferredPagesPlugin`, `ReferringUndefinedPagesPlugin`, `UndefinedPagesPlugin`, `UnusedPagesPlugin`, `CurrentTimePlugin`, `IfPlugin`, `AliasPlugin`, `RelationshipsPlugin`, `HubSetPlugin` and `JDBCPlugin` (disabled by default).
- **`[{TableOfContents}]` is not a `Plugin` class.** The Flexmark link post-processor (`PluginLinkNodePostProcessorState`) renders it directly.
- **Filters**: `StructuralSpinePageFilter` enforces the structural-spine rules (canonical IDs, clusters) at save time; `SchemaValidationPageFilter` validates frontmatter; `MathValidationPageFilter` flags suspect math; `FrontmatterDefaultsFilter` adds a default block to a page saved without one; `SpamFilter` and `ProfanityFilter` are the classic content filters. Core filters are registered in code with `FilterManager.addPageFilter( filter, priority )`; add-on filters are listed in `WEB-INF/filters.xml` (or the file named by `wikantik.filterConfig`).
- **Providers**: `FileSystemProvider` (plain files), `VersioningFileProvider` (files with history, the default) and `CachingProvider` (a decorator) for page storage.
- **Custom rendering**: Markdown via Flexmark with custom extensions (wikilinks, math, and others).

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
