# Wikantik Reader UI

A modern, Medium.com-inspired reading experience built with React.

> **Implementation Status:** This React SPA has been fully implemented in the `wikantik-frontend/` directory and is bundled into the WAR at `/app/`. The application includes: page viewing with rendered Markdown, inline editing, search, dark mode, metadata chips, change history, admin panel (Users, Content, Security tabs), and sidebar navigation with cluster grouping.

## Overview

A standalone React SPA that consumes Wikantik's REST API, providing a modern reading and editing experience alongside the traditional JSP templates.

## Architecture

### Core Concept

- **Standalone React SPA** deployed at `/app/`
- Consumes Wikantik REST API (`/api/`)
- Admin panel at `/app/admin/`
- Clean separation of concerns

### Technology Stack (Implemented)

| Layer | Choice | Notes |
|-------|--------|-------|
| Framework | React 18 | Industry standard, large ecosystem |
| Build | Vite | Fast HMR, modern defaults; integrated into Maven build |
| Routing | React Router v6 | Client-side routing |
| Styling | Custom CSS design system | No frameworks; CSS variables for theming |

## Project Structure

```
jspwiki-reader/                    # New Maven module
├── pom.xml
├── package.json
├── vite.config.ts                 # Vite for fast builds
├── tsconfig.json
├── src/
│   ├── main.tsx                   # Entry point
│   ├── App.tsx                    # Root component + routing
│   ├── api/
│   │   ├── client.ts              # API client (fetch wrapper)
│   │   ├── pages.ts               # Page API calls
│   │   ├── search.ts              # Search API
│   │   └── types.ts               # TypeScript interfaces
│   ├── components/
│   │   ├── layout/
│   │   │   ├── Header.tsx         # Minimal sticky header
│   │   │   ├── MobileNav.tsx      # Bottom navigation (mobile)
│   │   │   ├── Sidebar.tsx        # ToC sidebar (desktop)
│   │   │   └── ProgressBar.tsx    # Reading progress
│   │   ├── article/
│   │   │   ├── ArticleView.tsx    # Main article container
│   │   │   ├── ArticleHeader.tsx  # Title, meta, hero image
│   │   │   ├── ArticleBody.tsx    # Rendered content
│   │   │   ├── CodeBlock.tsx      # Syntax highlighted code
│   │   │   ├── WikiLink.tsx       # Internal link handling
│   │   │   └── ImageViewer.tsx    # Lightbox for images
│   │   ├── navigation/
│   │   │   ├── TableOfContents.tsx
│   │   │   ├── Breadcrumbs.tsx
│   │   │   └── RelatedPages.tsx
│   │   ├── search/
│   │   │   ├── SearchModal.tsx    # Cmd+K search
│   │   │   └── SearchResults.tsx
│   │   └── ui/                    # Primitives
│   │       ├── Button.tsx
│   │       ├── Card.tsx
│   │       ├── Skeleton.tsx       # Loading states
│   │       └── ThemeToggle.tsx
│   ├── hooks/
│   │   ├── useArticle.ts          # Fetch + cache article
│   │   ├── useReadingProgress.ts  # Scroll tracking
│   │   ├── useReadingTime.ts      # Word count calculation
│   │   ├── useTheme.ts            # Dark/light mode
│   │   ├── useMediaQuery.ts       # Responsive breakpoints
│   │   └── useScrollDirection.ts  # Header show/hide
│   ├── stores/
│   │   └── reader.ts              # Zustand store
│   ├── styles/
│   │   ├── global.css             # CSS reset, variables
│   │   ├── typography.css         # Font system
│   │   └── theme.css              # Color tokens
│   ├── utils/
│   │   ├── wikiParser.ts          # Transform wiki HTML
│   │   └── readingTime.ts
│   └── pages/
│       ├── HomePage.tsx           # Recent/featured pages
│       ├── ArticlePage.tsx        # Single article view
│       ├── SearchPage.tsx         # Search results
│       └── NotFoundPage.tsx
└── public/
    └── fonts/                     # Self-hosted fonts
```

## Design System

### Typography

**Font Stack:**
```css
/* Body - Georgia-based stack (no licensing needed) */
--font-body: Charter, 'Bitstream Charter', 'Sitka Text', Cambria, serif;

/* Headings - Clean sans-serif */
--font-heading: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;

/* Code - Monospace */
--font-code: 'SF Mono', SFMono-Regular, Consolas, 'Liberation Mono', Menlo, monospace;
```

**Type Scale (Major Third - 1.25):**
```css
--text-sm: 0.875rem;    /* 14px - metadata */
--text-base: 1.25rem;   /* 20px - body (larger than typical) */
--text-lg: 1.5rem;      /* 24px - lead paragraph */
--text-xl: 2rem;        /* 32px - h3 */
--text-2xl: 2.5rem;     /* 40px - h2 */
--text-3xl: 3rem;       /* 48px - h1/title */

--line-height-body: 1.7;
--line-height-heading: 1.2;
--letter-spacing-body: -0.003em;
```

### Layout

**Content Container:**
```css
.article-content {
    max-width: 680px;           /* Medium's magic number */
    margin: 0 auto;
    padding: 0 24px;
}

.article-content--wide {
    max-width: 1000px;          /* For tables, code blocks */
}

.article-hero-image {
    max-width: 100vw;           /* Full-bleed images */
    margin-left: calc(-50vw + 50%);
    width: 100vw;
}
```

**Responsive Breakpoints:**
```css
--breakpoint-sm: 480px;   /* Mobile */
--breakpoint-md: 728px;   /* Tablet */
--breakpoint-lg: 1080px;  /* Desktop */
```

### Color System

```css
:root {
  /* Colors - Light */
  --bg-primary: #ffffff;
  --bg-secondary: #f9fafb;
  --bg-tertiary: #f3f4f6;
  --text-primary: #111827;
  --text-secondary: #6b7280;
  --text-tertiary: #9ca3af;
  --border: #e5e7eb;
  --accent: #059669;

  /* Shadows */
  --shadow-sm: 0 1px 2px rgba(0,0,0,0.05);
  --shadow-md: 0 4px 6px rgba(0,0,0,0.07);

  /* Transitions */
  --transition-fast: 150ms ease;
  --transition-normal: 250ms ease;
}

[data-theme="dark"] {
  --bg-primary: #0f0f0f;
  --bg-secondary: #171717;
  --bg-tertiary: #262626;
  --text-primary: #f9fafb;
  --text-secondary: #9ca3af;
  --text-tertiary: #6b7280;
  --border: #374151;
  --accent: #10b981;
}
```

## Component Design

### Header (responsive)

```
┌────────────────────────────────────────────────────────┐
│  [Wiki Logo]              [Search] [ToC] [Theme] [☰]  │
└────────────────────────────────────────────────────────┘
```
- Transparent initially, solid on scroll
- Hides when scrolling down, reveals on scroll up
- Progress bar along bottom edge

### Article Header

```
┌────────────────────────────────────────────────────────┐
│                                                        │
│            Understanding Wiki Architecture             │  ← Title (h1)
│                                                        │
│            A deep dive into Wikantik internals          │  ← Subtitle (optional)
│                                                        │
│  [Avatar] John Smith · 8 min read · Dec 8, 2025       │  ← Metadata
│                                                        │
│  ┌──────────────────────────────────────────────────┐ │
│  │                  [Hero Image]                     │ │  ← Full-width
│  └──────────────────────────────────────────────────┘ │
│                                                        │
└────────────────────────────────────────────────────────┘
```

### Mobile Navigation (bottom bar)

```
┌─────────┬─────────┬─────────┬─────────┐
│  Home   │ Search  │Contents │  Edit   │
└─────────┴─────────┴─────────┴─────────┘
```

### Table of Contents (slide-in)

```
┌─────────────────────┐
│  Contents           │
│  ─────────────────  │
│  • Introduction     │  ← Current section highlighted
│  • Architecture     │
│    ◦ Components     │
│    ◦ Providers      │
│  • Implementation   │
│  • Conclusion       │
└─────────────────────┘
```
- Fixed position on wide screens (>1200px)
- Slide-out panel on narrower screens
- Highlights current section

## API Integration

```typescript
// src/api/types.ts
interface WikiPage {
  name: string;
  content: string;        // HTML rendered content
  lastModified: string;
  author: string;
  version: number;
  attachments?: Attachment[];
}

interface SearchResult {
  page: string;
  score: number;
  contexts: string[];     // Highlighted snippets
}

// src/api/client.ts
const API_BASE = '/api';  // Or full URL if separate origin

export async function fetchPage(name: string): Promise<WikiPage> {
  const res = await fetch(`${API_BASE}/pages/${encodeURIComponent(name)}`);
  if (!res.ok) throw new Error(`Page not found: ${name}`);
  return res.json();
}

export async function searchPages(query: string): Promise<SearchResult[]> {
  const res = await fetch(`${API_BASE}/search?query=${encodeURIComponent(query)}`);
  return res.json();
}
```

## Key React Hooks

```typescript
// src/hooks/useArticle.ts
import { useQuery } from '@tanstack/react-query';
import { fetchPage } from '../api/client';

export function useArticle(pageName: string) {
  return useQuery({
    queryKey: ['page', pageName],
    queryFn: () => fetchPage(pageName),
    staleTime: 5 * 60 * 1000,  // 5 minutes
  });
}

// src/hooks/useReadingProgress.ts
export function useReadingProgress() {
  const [progress, setProgress] = useState(0);

  useEffect(() => {
    const handleScroll = () => {
      const article = document.querySelector('article');
      if (!article) return;

      const { top, height } = article.getBoundingClientRect();
      const windowHeight = window.innerHeight;
      const scrolled = Math.max(0, -top);
      const total = height - windowHeight;

      setProgress(Math.min(100, (scrolled / total) * 100));
    };

    window.addEventListener('scroll', handleScroll, { passive: true });
    return () => window.removeEventListener('scroll', handleScroll);
  }, []);

  return { progress };
}

// src/hooks/useScrollDirection.ts
export function useScrollDirection() {
  const [direction, setDirection] = useState<'up' | 'down'>('up');
  const lastScrollY = useRef(0);

  useEffect(() => {
    const handleScroll = () => {
      const currentY = window.scrollY;
      const threshold = 10;

      if (Math.abs(currentY - lastScrollY.current) < threshold) return;

      setDirection(currentY > lastScrollY.current ? 'down' : 'up');
      lastScrollY.current = currentY;
    };

    window.addEventListener('scroll', handleScroll, { passive: true });
    return () => window.removeEventListener('scroll', handleScroll);
  }, []);

  return { scrollDirection: direction };
}

// src/hooks/useReadingTime.ts
export function useReadingTime(content?: string) {
  return useMemo(() => {
    if (!content) return 0;
    const text = content.replace(/<[^>]*>/g, '');
    const words = text.trim().split(/\s+/).length;
    return Math.ceil(words / 200);  // 200 wpm average
  }, [content]);
}
```

## Wiki Content Processing

```typescript
// src/utils/wikiParser.ts
export function processWikiContent(html: string): string {
  const doc = new DOMParser().parseFromString(html, 'text/html');

  // Transform wiki links to React Router links
  doc.querySelectorAll('a.wikipage').forEach(link => {
    const href = link.getAttribute('href');
    if (href?.startsWith('/wiki/')) {
      link.setAttribute('data-wiki-link', 'true');
      link.setAttribute('href', `/page/${href.replace('/wiki/', '')}`);
    }
  });

  // Add lazy loading to images
  doc.querySelectorAll('img').forEach(img => {
    img.setAttribute('loading', 'lazy');
    img.classList.add('article-image');
  });

  // Wrap tables for horizontal scroll
  doc.querySelectorAll('table').forEach(table => {
    const wrapper = doc.createElement('div');
    wrapper.className = 'table-wrapper';
    table.parentNode?.insertBefore(wrapper, table);
    wrapper.appendChild(table);
  });

  return doc.body.innerHTML;
}
```

## Maven Build Integration

```xml
<!-- jspwiki-reader/pom.xml -->
<project>
  <parent>
    <groupId>org.apache.jspwiki</groupId>
    <artifactId>jspwiki-builder</artifactId>
    <version>3.0.7-SNAPSHOT</version>
  </parent>

  <artifactId>jspwiki-reader</artifactId>
  <packaging>pom</packaging>
  <name>Wikantik Reader UI</name>

  <build>
    <plugins>
      <plugin>
        <groupId>com.github.eirslett</groupId>
        <artifactId>frontend-maven-plugin</artifactId>
        <version>1.15.0</version>
        <executions>
          <execution>
            <id>install-node-npm</id>
            <goals><goal>install-node-and-npm</goal></goals>
            <configuration>
              <nodeVersion>v20.10.0</nodeVersion>
            </configuration>
          </execution>
          <execution>
            <id>npm-install</id>
            <goals><goal>npm</goal></goals>
            <configuration>
              <arguments>ci</arguments>
            </configuration>
          </execution>
          <execution>
            <id>npm-build</id>
            <goals><goal>npm</goal></goals>
            <configuration>
              <arguments>run build</arguments>
            </configuration>
          </execution>
        </executions>
      </plugin>

      <!-- Copy built assets to WAR -->
      <plugin>
        <artifactId>maven-resources-plugin</artifactId>
        <executions>
          <execution>
            <id>copy-reader-assets</id>
            <phase>package</phase>
            <goals><goal>copy-resources</goal></goals>
            <configuration>
              <outputDirectory>
                ${project.parent.basedir}/wikantik-war/target/Wikantik/reader
              </outputDirectory>
              <resources>
                <resource>
                  <directory>dist</directory>
                </resource>
              </resources>
            </configuration>
          </execution>
        </executions>
      </plugin>
    </plugins>
  </build>
</project>
```

## Implementation Phases

### Phase 1: Project Setup -- COMPLETED
- Created `wikantik-frontend/` module
- Configured Vite + React
- Set up CSS design system architecture
- Implemented API client
- Basic routing with React Router v6

### Phase 2: Core Reading Experience -- COMPLETED
- Page viewing with rendered Markdown
- Inline editing
- Typography and spacing
- Responsive layout

### Phase 3: Navigation & Chrome -- COMPLETED
- Sidebar navigation with cluster grouping
- Metadata chips
- Change history viewing

### Phase 4: Search & Discovery -- COMPLETED
- Search functionality
- Home page with content listing

### Phase 5: Polish -- COMPLETED
- Dark mode
- Loading states
- Error states

### Phase 6: Integration -- COMPLETED
- Maven build integration (npm install + Vite build run automatically during WAR build)
- Deployed at `/app/` within the WAR
- Admin panel at `/app/admin/` with Users, Content, and Security tabs

## Deployment

The React SPA is embedded in the WAR and served from `/app/`. The Vite build is triggered automatically during the Maven WAR build (`npm install` + `vite build`). No separate hosting or CORS configuration is needed.

### Architectural Decisions (Resolved)

| Decision | Choice |
|----------|--------|
| Module Location | `wikantik-frontend/` module |
| Deployment Model | Embedded in WAR at `/app/` |
| Authentication | Respects ACLs via REST API session |
| Feature Scope | Full: reading, editing, search, dark mode, admin panel |

## Risks & Mitigations

| Risk | Mitigation |
|------|------------|
| Plugin compatibility | Create plugin output wrapper styles |
| Editor experience | Fall back to haddock for editing |
| Accessibility | Ensure WCAG 2.1 AA compliance from start |
| Browser support | Target modern browsers, graceful degradation |
| Performance | Lazy load images, minimize JS |
