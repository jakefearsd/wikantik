---
canonical_id: 01KQ0P44YRV3CYD5BFYA8W1TTW
title: Web Components
type: article
cluster: frontend-development
status: active
date: '2026-04-26'
summary: What Web Components actually are — custom elements, shadow DOM, templates
  — and the cases where they fit vs. where framework components remain the right
  answer.
tags:
- web-components
- custom-elements
- shadow-dom
- frontend
related:
- ResponsiveDesignPrinciples
- TypeScriptFundamentals
- ServerSideRendering
- CssArchitecturePatterns
hubs:
- FrontendDevelopmentHub
---
# Web Components

Web Components are a set of browser-native APIs for building reusable, encapsulated UI components. The bundle: custom elements (define new HTML tags), shadow DOM (style isolation), and HTML templates.

They've been around for over a decade. Adoption has been steady but not overwhelming — frameworks (React, Vue) dominate.

This page covers what Web Components are and when they make sense.

## The technologies

### Custom Elements

Define your own HTML tags:

```javascript
class MyButton extends HTMLElement {
    constructor() {
        super();
        this.attachShadow({ mode: 'open' });
        this.shadowRoot.innerHTML = `
            <button><slot></slot></button>
            <style>
                button { background: blue; color: white; }
            </style>
        `;
    }
}

customElements.define('my-button', MyButton);
```

```html
<my-button>Click me</my-button>
```

The browser knows about `<my-button>` after `customElements.define`.

### Shadow DOM

Encapsulates internal structure and styles. Shadow DOM contents don't leak out; outside styles don't leak in.

The "open" mode allows JS to access the shadow root; "closed" hides it entirely. Open is more common.

### HTML Templates

The `<template>` element holds DOM that doesn't render until activated:

```html
<template id="my-template">
    <div>Reusable content</div>
</template>
```

Templates can be cloned and inserted; they're inert until used.

### Slots

Inside shadow DOM, `<slot>` defines insertion points for content from outside:

```html
<!-- Inside shadow DOM -->
<div class="card">
    <slot name="header"></slot>
    <slot></slot>  <!-- default slot -->
</div>

<!-- Outside -->
<my-card>
    <h2 slot="header">Title</h2>
    <p>Body content</p>
</my-card>
```

## When Web Components win

### Cross-framework reuse

A Web Component works in any framework or no framework. If you need a button used in React, Vue, and plain HTML, Web Components are the only standard solution.

### Long-term stability

Web Components are part of the platform. They'll work in 10 years. React, Vue may have moved on.

### Design system distribution

A design system shipping Web Components can be consumed by any team regardless of their framework choice. Adobe, Salesforce, Microsoft do this.

### Style isolation

Shadow DOM provides genuine isolation. CSS conflicts between components disappear. Useful when integrating with unknown environments.

### Embedding in other pages

A widget you embed in others' websites: Web Component is robust to whatever the host page is doing.

## When framework components win

### Within a single framework

If your whole app is React, React components work better than Web Components. Native integration; ecosystem; tooling.

### Rich state and behavior

React's hooks, Vue's composition API are more ergonomic than the raw Web Components APIs.

### Ecosystem

React/Vue have massive ecosystems. Web Components ecosystem exists but is smaller.

### Server-side rendering

SSR with Web Components works but is awkward. Frameworks have first-class SSR support.

## Hybrid approaches

### React + Web Components

Use Web Components for cross-framework or design system pieces; use React components for app-specific UI.

### Lit / Stencil

Frameworks built on Web Components. Provide better DX while still producing standard Web Components.

- **Lit**: Google's framework. Lightweight; reactive properties; templates.
- **Stencil**: Ionic's framework. Full SSR support; TypeScript-first.

For teams committing to Web Components as the primary technology, these reduce the boilerplate.

## Specific patterns

### Reactive properties

```javascript
class MyButton extends HTMLElement {
    static observedAttributes = ['variant'];

    attributeChangedCallback(name, oldValue, newValue) {
        if (name === 'variant') this.update();
    }

    update() {
        // re-render based on variant
    }
}
```

Lit and Stencil simplify this.

### Custom events

Web Components fire DOM events that any consumer can listen to:

```javascript
this.dispatchEvent(new CustomEvent('selected', {
    detail: { value: this.value },
    bubbles: true,
    composed: true  // pierces shadow DOM boundaries
}));
```

Consumers listen with standard `addEventListener`. Framework-agnostic.

### Form participation

Web Components can participate in forms via the `ElementInternals` API. Modern; not in all browsers.

## Common failure patterns

- **Web Components everywhere within one framework.** Slower; less ergonomic than framework components.
- **Heavy use of slots without thinking.** Performance and complexity costs.
- **Closed shadow DOM where open works.** Makes debugging harder.
- **No SSR thought.** Web Components without SSR strategy can have hydration issues.
- **No accessibility.** Shadow DOM can complicate ARIA; need explicit work.

## A reasonable position

For most app development:
- Pick a framework (React, Vue, Svelte, etc.)
- Use framework components for app code

For specific cases:
- Design systems → Web Components (cross-framework)
- Embeddable widgets → Web Components
- Long-lived components that outlast framework choices → Web Components

## Further Reading

- [ResponsiveDesignPrinciples](ResponsiveDesignPrinciples) — How components handle layout
- [TypeScriptFundamentals](TypeScriptFundamentals) — Type-safe components
- [ServerSideRendering](ServerSideRendering) — SSR considerations
- [CssArchitecturePatterns](CssArchitecturePatterns) — Style architecture
- [FrontendDevelopment Hub](FrontendDevelopmentHub) — Cluster index
