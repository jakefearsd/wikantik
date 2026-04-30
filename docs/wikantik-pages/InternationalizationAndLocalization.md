---
canonical_id: 01KQ0P44R6826C3W9G6K2ZX1H9
title: Internationalization and Localization
type: article
cluster: frontend-development
status: active
date: '2026-04-26'
summary: How to build apps that work in multiple languages and regions — i18n libraries,
  RTL languages, date/number formatting, and the patterns that prevent the worst i18n
  bugs.
tags:
- i18n
- l10n
- internationalization
- localization
- frontend
related:
- WebAccessibilityGuide
- ResponsiveDesignPrinciples
- TypeScriptFundamentals
hubs:
- FrontendDevelopmentHub
---
# Internationalization and Localization

Internationalization (i18n): designing your app so it can be localized.
Localization (l10n): adapting it for a specific language and region.

i18n is structural; l10n is content. Both matter for global apps. Done well, supporting a new language is straightforward. Done poorly, every language addition is painful.

## The basics

### Strings as keys

Don't hard-code text:

```javascript
// Bad: text in code
<button>Click me</button>

// Good: keyed
<button>{t('button.click')}</button>
```

The key (`button.click`) maps to text in the user's language via a translation table.

### Translation tables

Per-language JSON files:

```json
// en.json
{
    "button.click": "Click me",
    "user.greeting": "Hello, {name}!"
}

// es.json
{
    "button.click": "Haz clic",
    "user.greeting": "Hola, {name}!"
}
```

The library substitutes variables (`{name}`).

### Formatting

Numbers, dates, and currencies vary by locale:

```javascript
new Intl.NumberFormat('en-US').format(1234.56);     // "1,234.56"
new Intl.NumberFormat('de-DE').format(1234.56);     // "1.234,56"

new Intl.DateTimeFormat('en-US').format(new Date()); // "4/26/2026"
new Intl.DateTimeFormat('en-GB').format(new Date()); // "26/04/2026"
```

`Intl` is built into modern JavaScript. Use it; don't write custom formatting.

## Pluralization

Different languages have different plural rules:

- English: 1 vs many (singular/plural)
- Russian: 1, 2-4, 5+ (three forms)
- Arabic: zero, one, two, few, many, other (six forms)

Your translation library should handle this:

```javascript
t('items.count', { count: 1 });   // "1 item"
t('items.count', { count: 5 });   // "5 items"
```

The library picks the right plural form per language.

## Right-to-left (RTL) languages

Arabic, Hebrew, Persian, Urdu read right-to-left. Layouts must mirror.

CSS approach:

```css
[dir="rtl"] .sidebar {
    /* Reverse the layout */
}

/* Or use logical properties */
.sidebar {
    margin-inline-start: 16px;  /* Works in both LTR and RTL */
}
```

Modern CSS logical properties (`margin-inline-start`, `padding-block-end`, etc.) handle RTL automatically.

Set `<html dir="rtl">` for RTL languages; CSS adjusts.

## Libraries

### React

- **react-intl** (FormatJS): comprehensive
- **i18next**: framework-agnostic; widely used
- **lingui**: smaller; modern

### Vue

- **vue-i18n**: official solution

### Vanilla / framework-agnostic

- **i18next**
- **MessageFormat**

### Server-side i18n

Same libraries usually work server-side; some node-specific patterns exist.

## Translation workflow

### Source language

Pick one language as canonical (usually English). Translations derive from it.

### Translation management systems

Tools like Lokalise, Phrase, Crowdin help manage translations across languages. Translators work in the tool; output goes back to your repo.

For small apps, JSON files in git work. For larger apps, a TMS reduces friction.

### Pseudo-localization

Run your app with strings transformed (every "a" becomes "ä", strings extended by 30%). Catches:
- Hard-coded text
- Layouts that don't accommodate longer text
- Encoding issues

Cheap technique; finds many bugs before real translation begins.

## Common patterns

### Locale detection

```javascript
const locale = navigator.language || 'en-US';
```

Browser tells you. User can override in app settings.

### Locale stored per user

For logged-in users, save locale preference. For guests, default from browser.

### Currency

Different from locale. A user might prefer English but use Euros. Store separately.

### Time zones

Even more independent of locale. Store user's time zone explicitly.

## Translation quality

Machine translation has improved but still produces awkward results:

- Acceptable for less-prominent text
- Not acceptable for marketing copy, errors, key UX
- Native-speaker review for important strings

For important translations, hire human translators. Machine is a starting draft, not a final answer.

## Pitfalls

### Concatenated strings

```javascript
// Bad
"You have " + count + " messages"

// Good
t('messages.count', { count })
```

In some languages, the word order is different. The first form can't be translated correctly.

### Embedded HTML in translations

```javascript
t('terms', { link: <a>terms</a> })
```

Some libraries support this; some require workarounds. Check yours.

### Right-to-left bidirectional text

Hebrew with embedded English numbers, or vice versa. Browsers handle most cases; specific complex layouts may need explicit Unicode bidi marks.

### Plural expressions

```javascript
// Bad
count === 1 ? "1 item" : `${count} items`

// Good
t('items.count', { count })
```

Hard-coded plurals don't work for languages with multi-form plurals.

### Date formats

```javascript
// Bad
date.toString()  // browser-specific format

// Good
new Intl.DateTimeFormat(locale, options).format(date)
```

## Common failure patterns

- **Hard-coded strings.** Can't translate without code changes.
- **No pseudo-localization testing.** Bugs found in real translation phase.
- **Concatenated strings.** Untranslatable in some languages.
- **Wrong plural handling.** Looks fine in English; broken elsewhere.
- **No RTL testing.** Layout broken for Arabic users.
- **Locale = country.** It's not; locale is language + variant.
- **Time zones ignored.** Times shown in server time; users confused.

## Further Reading

- [WebAccessibilityGuide](WebAccessibilityGuide) — i18n is part of accessibility
- [ResponsiveDesignPrinciples](ResponsiveDesignPrinciples) — Layouts must accommodate text length variation
- [TypeScriptFundamentals](TypeScriptFundamentals) — Type-safe translation keys
- [FrontendDevelopment Hub](FrontendDevelopmentHub) — Cluster index
