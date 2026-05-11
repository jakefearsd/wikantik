---
summary: WCAG basics, ARIA when needed, keyboard navigation, screen reader patterns
  — the foundations of building accessible web UIs and the testing practices that
  keep them that way.
date: '2026-04-26'
cluster: frontend-development
related:
- ResponsiveDesignPrinciples
- FormHandlingAndValidation
- WebComponents
canonical_id: 01KQ0P44YQNR6NWP1MB9111W9Q
type: article
title: Web Accessibility Guide
tags:
- accessibility
- a11y
- wcag
- aria
- keyboard-navigation
status: active
hubs:
- FrontendDevelopmentHub
- WebComponents Hub
---
# Web Accessibility Guide

Web accessibility (a11y) means people with disabilities can use the web. Not just blind users with screen readers — also keyboard-only users, motor-impaired users, color-blind users, users with cognitive disabilities, deaf users.

It's also legally required in many jurisdictions and a moral baseline for public-facing software.

This page covers the foundations.

## WCAG

Web Content Accessibility Guidelines. The standard. WCAG 2.1 AA is the common compliance target.

Four principles (POUR):

- **Perceivable**: users can perceive content (alt text, captions, sufficient contrast)
- **Operable**: users can operate the UI (keyboard navigation, no time limits, no seizure-inducing content)
- **Understandable**: users understand the content and operation (clear language, consistent navigation, error messages)
- **Robust**: works with assistive technologies (valid HTML, proper ARIA)

WCAG has specific success criteria. Most are common-sense rules that good design follows naturally.

## Semantic HTML

The single biggest accessibility win: use the right HTML elements.

```html
<!-- Bad: a div pretending to be a button -->
<div onClick={handleClick}>Click me</div>

<!-- Good: an actual button -->
<button onClick={handleClick}>Click me</button>
```

The button:
- Receives keyboard focus
- Activates on Enter/Space
- Announces as "button" to screen readers
- Has hover/focus/active states

Reimplementing buttons with divs requires recreating all of this with ARIA. Just use the button.

Semantic elements that matter:
- `<button>` for buttons (not links that look like buttons)
- `<a href>` for navigation (not buttons that navigate)
- `<form>` for forms
- `<nav>`, `<main>`, `<aside>` for landmarks
- `<h1>`-`<h6>` for headings (in proper order)
- `<label>` for form fields

## ARIA when semantic HTML isn't enough

ARIA (Accessible Rich Internet Applications) provides attributes for accessibility info that HTML doesn't natively express:

```html
<button aria-label="Close dialog" aria-expanded="false">
    <svg>...</svg>  <!-- icon -->
</button>
```

The first rule of ARIA: don't use ARIA. Use semantic HTML when possible.

The second rule: when ARIA is needed, learn the patterns. The W3C's ARIA Authoring Practices Guide is the canonical source.

Common use cases:
- Custom components (autocomplete, modal, tabs) — ARIA describes the role and state
- Screen-reader-only labels (`aria-label`, `aria-labelledby`)
- Live regions for dynamic content (`aria-live`)
- Hiding decorative content (`aria-hidden="true"`)

## Keyboard navigation

Not everyone uses a mouse. Power users and users with motor disabilities use keyboards.

Required:
- All interactive elements reachable via Tab
- Focus is visible (keyboard focus ring)
- Logical tab order (matches visual order)
- Standard key bindings (Enter activates, Escape closes modals)
- No keyboard traps (you can always tab out)

Common failures:
- Custom components that don't receive focus
- Focus rings disabled with CSS `outline: none` (without replacement)
- Focus trapped in modals with no escape
- Tab order that jumps around the page

Test: navigate your entire app with only the keyboard. Notice every place it doesn't work.

## Screen reader testing

Screen readers convert visual content to speech (or braille). Test with one:

- **VoiceOver** on macOS / iOS
- **NVDA** on Windows (free)
- **JAWS** on Windows (commercial)
- **TalkBack** on Android

Open your app; navigate by Tab and arrow keys; listen.

Common failures:
- Images without alt text
- Buttons with only icon (no accessible name)
- Form fields without labels
- Live updates that aren't announced
- Reading order doesn't match visual order

You don't need to be expert; basic listening reveals most problems.

## Color contrast

WCAG AA: 4.5:1 for body text, 3:1 for large text.

Tools test contrast (browser DevTools, WebAIM Contrast Checker). Test your design system colors at design time, not after.

Don't communicate solely with color:
- Error states: color + icon + text, not just red
- Required fields: asterisk + visual indication, not just bolding

## Forms

Forms are common accessibility failure points.

Required:
- Every input has a `<label>` associated by `for`/`id`
- Required fields are marked (`required` attribute + visual indication)
- Validation errors are announced and linked to fields (`aria-describedby`)
- Error messages are clear ("Email is required" not "Invalid")
- Form submission errors are announced

See [FormHandlingAndValidation](FormHandlingAndValidation).

## Common patterns

### Skip links

A link at the top of the page that skips to main content. Hidden until focused.

```html
<a href="#main" class="skip-link">Skip to main content</a>
```

For keyboard users navigating around extensive headers/navigation.

### Focus management

After a modal opens, focus moves into the modal. After it closes, focus returns to the trigger. Without this, keyboard users get lost.

Modern modal libraries handle this; custom implementations need explicit work.

### Live regions

For dynamic content that should be announced:

```html
<div role="status" aria-live="polite">
    Item added to cart
</div>
```

Screen readers announce changes to live regions.

## Tools

### Axe

Browser extension and library. Runs accessibility tests on a page; reports violations. The standard automated tool.

Most accessibility issues axe catches; more nuanced ones require manual testing.

### Lighthouse

Built into Chrome DevTools. Has an accessibility audit (uses axe under the hood).

### Wave

Browser extension. Visual accessibility audit.

### Manual testing

Automated tools catch ~30% of issues. Manual testing (keyboard, screen reader) catches the rest.

## Common failure patterns

- **Adding accessibility at the end.** Retrofitting is expensive; design accessibility in from the start.
- **`<div>` for everything.** Loses semantic meaning.
- **Decorative ARIA.** Extra ARIA on already-semantic HTML; breaks more than it fixes.
- **Test with axe; declare done.** Misses 70% of issues.
- **Custom components without keyboard support.** Work for mouse; fail for keyboard.
- **Inadequate color contrast.** Text not visible to many users.
- **Time limits without warnings.** Failed by users with cognitive disabilities.

## Further Reading

- [ResponsiveDesignPrinciples](ResponsiveDesignPrinciples) — Responsive must be accessible
- [FormHandlingAndValidation](FormHandlingAndValidation) — Forms are common failure points
- [WebComponents](WebComponents) — Custom components need explicit accessibility
- [FrontendDevelopment Hub](FrontendDevelopmentHub) — Cluster index
