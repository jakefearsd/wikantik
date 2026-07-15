export const CONNECTOR_TYPES = {
  webcrawler: {
    label: 'Web crawler',
    icon: '🕸',
    blurb: 'Crawl a website by following links from seed URLs.',
    goodFor: 'Documentation sites and blogs without a sitemap or feed.',
    secrets: [],
    fields: [
      {
        name: 'seeds',
        type: 'list',
        label: 'Seed URLs',
        required: true,
        help: 'One URL per line. The crawl starts here and follows same-host links.',
      },
      {
        name: 'path_prefix',
        type: 'text',
        label: 'Path prefix',
        help: 'Only crawl URLs whose path starts with this (e.g. /docs/).',
      },
      {
        name: 'max_pages',
        type: 'number',
        label: 'Max pages',
        default: 100,
      },
      {
        name: 'max_depth',
        type: 'number',
        label: 'Max link depth',
        default: 3,
      },
      {
        name: 'delay_ms',
        type: 'number',
        label: 'Delay between fetches (ms)',
        default: 1000,
      },
      {
        name: 'respect_robots',
        type: 'bool',
        label: 'Respect robots.txt',
        default: true,
      },
      {
        name: 'same_host_only',
        type: 'bool',
        label: 'Stay on the seed host',
        default: true,
      },
    ],
    authGuide: null,
    expectations:
      'The first sync fetches up to your max-pages cap and creates one wiki page per crawled page, with the page name combining your configured prefix (if any) and the URL path flattened (slashes become underscores). Cluster and tags are applied according to your source settings. Page bodies are machine-managed — any edits you make to them will be overwritten on the next sync, but frontmatter curation you add is preserved across syncs. Pages appear in search after the async indexer catches up, typically within a minute.',
  },

  sitemap: {
    label: 'Sitemap',
    icon: '🗺',
    blurb: 'Fetch pages listed in a sitemap.',
    goodFor: 'Websites with a sitemap.xml that you want to index systematically.',
    secrets: [],
    fields: [
      {
        name: 'sitemap_urls',
        type: 'list',
        label: 'Sitemap URLs',
        required: true,
        help: 'One URL per line. Each should point to a sitemap.xml or index of sitemaps.',
      },
      {
        name: 'max_pages',
        type: 'number',
        label: 'Max pages',
        default: 500,
      },
      {
        name: 'delay_ms',
        type: 'number',
        label: 'Delay between fetches (ms)',
        default: 1000,
      },
      {
        name: 'respect_robots',
        type: 'bool',
        label: 'Respect robots.txt',
        default: true,
      },
      {
        name: 'same_host_only',
        type: 'bool',
        label: 'Stay on the seed host',
        default: true,
      },
    ],
    authGuide: null,
    expectations:
      'The first sync parses your sitemap(s) and fetches up to your max-pages cap, creating one wiki page per unique URL discovered. Each page name combines your configured prefix (if any) with the URL path flattened (slashes become underscores). Cluster and tags are applied according to your source settings. Page bodies are machine-managed — edits to them will be overwritten on the next sync, but frontmatter curation is preserved. Pages appear in search after the async indexer catches up, typically within a minute.',
  },

  feed: {
    label: 'Feed',
    icon: '📰',
    blurb: 'Subscribe to an RSS or Atom feed.',
    goodFor: 'Blogs and news feeds you want to archive or make searchable.',
    secrets: [],
    fields: [
      {
        name: 'feed_urls',
        type: 'list',
        label: 'Feed URLs',
        required: true,
        help: 'One URL per line. Each should be an RSS, RDF, or Atom feed.',
      },
      {
        name: 'max_items',
        type: 'number',
        label: 'Max items per feed',
        default: 100,
      },
      {
        name: 'fetch_full_articles',
        type: 'bool',
        label: 'Fetch full articles',
        default: true,
        help: 'Fetch each linked article page for full content; off = use the summary embedded in the feed.',
      },
      {
        name: 'delay_ms',
        type: 'number',
        label: 'Delay between fetches (ms)',
        default: 1000,
      },
      {
        name: 'respect_robots',
        type: 'bool',
        label: 'Respect robots.txt',
        default: true,
      },
      {
        name: 'same_host_only',
        type: 'bool',
        label: 'Stay on the seed host',
        default: true,
      },
    ],
    authGuide: null,
    expectations:
      'The first sync reads your feed(s) and creates one wiki page per item, up to your max-items cap per feed. Each page name combines your configured prefix (if any) with the feed title and item title, flattened (non-alphanumerics become underscores). If you enabled fetching full articles, the sync will download the linked page content. Cluster and tags are applied according to your source settings. Page bodies are machine-managed — edits will be overwritten on the next sync, but frontmatter curation is preserved. Pages appear in search after the async indexer catches up, typically within a minute.',
  },

  github: {
    label: 'GitHub',
    icon: '🐙',
    blurb: 'Sync markdown files from a GitHub repository.',
    goodFor: 'Documentation repositories, engineering wikis, and archived discussions.',
    secrets: ['token'],
    fields: [
      {
        name: 'repo',
        type: 'text',
        label: 'Repository',
        required: true,
        help: 'owner/name, e.g. jakefearsd/wikantik',
      },
      {
        name: 'branch',
        type: 'text',
        label: 'Branch',
        help: 'Blank = default branch. Enter the branch name to sync from a specific branch.',
      },
      {
        name: 'path_prefix',
        type: 'text',
        label: 'Path prefix',
        help: 'Only sync files under this path, e.g. docs/',
      },
      {
        name: 'max_files',
        type: 'number',
        label: 'Max files',
        default: 500,
      },
    ],
    authGuide: {
      secretName: 'token',
      steps: [
        'Open GitHub → Settings → Developer settings → Fine-grained personal access tokens.',
        'Generate new token; under Repository access choose Only select repositories and pick just this repo.',
        'Under Permissions → Repository permissions set Contents: Read-only. Grant nothing else.',
        'Set an expiration you can live with (you will paste a fresh token here when it expires).',
        'Generate and copy the token now — GitHub shows it only once.',
      ],
    },
    expectations:
      'The first sync walks the repository tree and creates one wiki page per markdown file (up to your max-files cap), with the page name combining your configured prefix (if any) and the file path flattened (slashes become underscores; .md extension removed). Cluster and tags are applied according to your source settings. Page bodies are machine-managed — edits will be overwritten on the next sync, but frontmatter curation is preserved. Pages appear in search after the async indexer catches up, typically within a minute.',
  },

  confluence: {
    label: 'Confluence',
    icon: '🌀',
    blurb: 'Sync pages from a Confluence space.',
    goodFor: 'Company wikis and knowledge bases running on Atlassian Confluence.',
    secrets: ['api_token'],
    fields: [
      {
        name: 'base_url',
        type: 'text',
        label: 'Confluence base URL',
        required: true,
        help: 'https://your-site.atlassian.net/wiki',
      },
      {
        name: 'space_key',
        type: 'text',
        label: 'Space key',
        required: true,
        help: 'The Confluence space key (shown in space settings or URL).',
      },
      {
        name: 'email',
        type: 'text',
        label: 'Account email',
        required: true,
        help: 'The Atlassian account email the API token belongs to.',
      },
      {
        name: 'max_pages',
        type: 'number',
        label: 'Max pages',
        default: 500,
      },
    ],
    authGuide: {
      secretName: 'api_token',
      steps: [
        'Open id.atlassian.com → Account settings → Security → Create and manage API tokens.',
        'Create API token; give it a label like "wikantik sync".',
        'Copy the token — Atlassian shows it only once.',
        'The token authenticates together with your account email (Basic auth) — enter that email in the Source step.',
      ],
    },
    expectations:
      'The first sync lists every page in the space and creates one wiki page per Confluence page (up to your max-pages cap), with the page name combining your configured prefix (if any) and the Confluence page title flattened (non-alphanumerics become underscores). Cluster and tags are applied according to your source settings. Page bodies are machine-managed — edits will be overwritten on the next sync, but frontmatter curation is preserved. Pages appear in search after the async indexer catches up, typically within a minute.',
  },

  gdrive: {
    label: 'Google Drive',
    icon: '📁',
    blurb: 'Sync files from Google Drive folders.',
    goodFor: 'Collaborative documents, design docs, and shared drives with mixed file types.',
    secrets: ['client_secret', 'refresh_token'],
    fields: [
      {
        name: 'folder_ids',
        type: 'list',
        label: 'Folder IDs',
        required: true,
        help: 'Folder IDs from the Drive URL: drive.google.com/drive/folders/<THIS>',
      },
      {
        name: 'max_files',
        type: 'number',
        label: 'Max files',
        default: 500,
      },
      {
        name: 'export_mime',
        type: 'text',
        label: 'Export MIME type',
        default: 'text/markdown',
        help: 'Google Docs export format: text/markdown, text/html, text/plain, etc.',
      },
      {
        name: 'redirect_uri',
        type: 'text',
        label: 'Redirect URI (optional)',
        help: 'Leave blank to use this wiki\'s callback URL — you will register it in Google Cloud in the next step.',
      },
      {
        name: 'client_id',
        type: 'text',
        label: 'Client ID',
        help: 'OAuth2 Client ID from Google Cloud Console (shared with client_secret).',
      },
    ],
    authGuide: {
      secretName: 'client_secret',
      steps: [
        'Open console.cloud.google.com → create (or pick) a project.',
        'APIs & Services → Enable APIs → enable the Google Drive API.',
        'APIs & Services → OAuth consent screen → External → fill the minimum fields → add yourself as a test user.',
        'APIs & Services → Credentials → Create credentials → OAuth client ID → Web application.',
        'Add the redirect URI shown below exactly as printed, then create.',
        'Copy the Client ID into the Source step and paste the Client secret here.',
        'After saving both, click Authorize with Google — you will be sent to Google\'s consent screen and back here.',
      ],
    },
    expectations:
      'After OAuth authorization, the first sync walks your Google Drive folders and exports files (up to your max-files cap) in your chosen format, creating one wiki page per file. Each page name combines your configured prefix (if any) with the folder hierarchy and filename flattened (slashes and non-alphanumerics become underscores). Cluster and tags are applied according to your source settings. Page bodies are machine-managed — edits will be overwritten on the next sync, but frontmatter curation is preserved. Pages appear in search after the async indexer catches up, typically within a minute.',
  },
};

export const TYPE_ORDER = ['webcrawler', 'sitemap', 'feed', 'github', 'confluence', 'gdrive'];
