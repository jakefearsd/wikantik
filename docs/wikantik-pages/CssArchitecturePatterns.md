---
canonical_id: 01KQ0P44P40EG8EYKE2B4EXE0C
title: CSS Architecture Patterns
type: article
cluster: frontend-development
status: active
date: '2026-04-26'
summary: BEM, atomic CSS, CSS-in-JS, CSS Modules — the patterns for organizing CSS
  at scale, the trade-offs each makes, and the modern approach that has won out.
tags:
- css
- bem
- atomic-css
- css-in-js
- tailwind
related:
- ResponsiveDesignPrinciples
- WebComponents
- ServerSideRendering
hubs:
- FrontendDevelopmentHub
---
# CSS Architecture Patterns

CSS at scale is hard. Global namespace; specificity battles; the dreaded `!important`. Several methodologies attempt to make CSS manageable at scale. Each has trade-offs.

This page covers the major patterns and the modern consensus.

## The problem

Without methodology, CSS at scale produces:

- Style conflicts (`.button` defined in 5 files)
- Specificity wars (`.modal .button.primary` vs. `.button.primary.large`)
- Dead CSS (rules nobody uses; afraid to delete)
- Cascading dependencies (changing one rule breaks unrelated things)

The methodologies try to prevent this.

## BEM (Block-Element-Modifier)

A naming convention:

```css
.card { }                    /* Block */
.card__title { }             /* Element */
.card__title--large { }      /* Modifier */
```

Names encode hierarchy. Global namespace; conflicts are visible in names.

```html
<div class="card card--featured">
    <h2 class="card__title card__title--large">Title</h2>
    <div class="card__body">Body</div>
</div>
```

Pros:
- Predictable
- Easy to grep
- No tooling needed

Cons:
- Verbose
- Long class names
- Discipline required

Was popular 2015-2020; less common now.

## CSS Modules

Locally-scoped class names. The build tool transforms:

```css
/* Card.module.css */
.title { color: blue; }
```

```javascript
import styles from './Card.module.css';
<h2 className={styles.title}>Title</h2>
```

The build outputs unique class names per file; no global conflicts.

Pros:
- Local scoping; no naming clashes
- Standard CSS
- Build-tool magic but understood

Cons:
- File coupling (classes tied to specific files)
- Awkward for shared utilities

Common in React projects.

## CSS-in-JS

Styles in JS:

```javascript
// styled-components
const Button = styled.button`
    background: ${props => props.primary ? 'blue' : 'gray'};
    color: white;
    padding: 8px 16px;
`;

// emotion
const buttonStyle = css`
    background: blue;
`;
```

Pros:
- Dynamic styles based on props
- Component-scoped
- Type-safe with TypeScript
- Theme support

Cons:
- Runtime cost (styles computed at render)
- Bundle size
- Performance issues at scale
- The "what color is this button" debugging

CSS-in-JS dominated 2018-2022 in React; some teams are moving away due to performance.

## Atomic CSS / Tailwind

Single-purpose utility classes:

```html
<button class="bg-blue-500 hover:bg-blue-700 text-white px-4 py-2 rounded">
    Click me
</button>
```

Each class does one thing. No custom CSS for this button.

Pros:
- No custom CSS to write
- Predictable output size (utilities are bounded)
- Fast (just CSS, no JS overhead)
- Tools (Tailwind) make it ergonomic
- Easy to delete components (no orphaned CSS)

Cons:
- HTML is verbose
- Initial learning curve
- Inline-style-feeling

Tailwind is the dominant atomic CSS implementation. Has become the modern default for many React/Vue projects.

## Vanilla CSS / No methodology

Just write CSS. No conventions; rely on developer discipline.

Pros: simple; no tooling.
Cons: doesn't scale; conflicts pile up.

For tiny projects, fine. Beyond a few hundred lines, methodology helps.

## The modern consensus

For most new React/Vue/Svelte projects (2024+):

- **Tailwind** for utility-first styling
- **CSS Modules** when component-specific styles needed
- **CSS variables** for theming

CSS-in-JS adoption has slowed for new projects. The performance issues and the complexity haven't justified the dynamic-styling benefits for many teams.

For design systems or component libraries, Web Components with shadow DOM provide their own isolation; can use plain CSS.

## Specific guidance

### Don't fight specificity

If you need `!important`, you're doing something wrong. Restructure or use a methodology that prevents specificity conflicts.

### Reset / normalize

`normalize.css` or similar. Provides consistent baseline across browsers.

### Modern features over old

CSS Grid, Flexbox, custom properties (CSS variables), container queries. Modern CSS is much more capable than 2010 CSS.

### Performance

CSS is render-blocking. Smaller is better. Don't ship 500 KB of CSS.

### Dark mode

CSS variables + media query is the modern approach:

```css
:root {
    --bg: white;
    --text: black;
}

@media (prefers-color-scheme: dark) {
    :root {
        --bg: black;
        --text: white;
    }
}

body {
    background: var(--bg);
    color: var(--text);
}
```

## Common failure patterns

- **Mixing methodologies.** BEM here, CSS-in-JS there, plain CSS elsewhere — confusing.
- **CSS-in-JS for everything in performance-sensitive apps.** Slow.
- **Tailwind without configuration.** Default Tailwind is huge; configure for your project.
- **No design system.** Every component reinvents colors, spacing, typography.
- **Dead CSS.** Rules that nothing uses; afraid to delete. Use coverage tools.

## Further Reading

- [ResponsiveDesignPrinciples](ResponsiveDesignPrinciples) — Layout patterns
- [WebComponents](WebComponents) — Style isolation via shadow DOM
- [ServerSideRendering](ServerSideRendering) — CSS in SSR
- [FrontendDevelopment Hub](FrontendDevelopmentHub) — Cluster index
