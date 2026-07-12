import { defineConfig } from 'vitepress'

// Base path + canonical host are env-driven so the same config works locally
// (base '/') and on GitHub Pages project site (base '/gavio/'). The Pages
// workflow sets DOCS_BASE and DOCS_HOSTNAME.
const base = process.env.DOCS_BASE ?? '/'
const HOSTNAME = process.env.DOCS_HOSTNAME ?? 'https://gavio.io'

export default defineConfig({
  base,
  lang: 'en-US',
  title: 'Gavio',
  titleTemplate: ':title · Gavio — AI request runtime',
  description:
    'AI request runtime and inspector for production systems. PII protection, audit trails, reliability, cost intelligence, cost governance, policy packs, and tool runtime checks as composable interceptors.',

  appearance: 'dark',
  cleanUrls: true,
  lastUpdated: true,

  // Generates /sitemap.xml for search engines. Use a trailing-slash hostname
  // and relative item urls so the base path (e.g. /gavio/) is preserved.
  sitemap: {
    hostname: HOSTNAME.endsWith('/') ? HOSTNAME : HOSTNAME + '/',
    transformItems: (items) =>
      items.map((item) => ({ ...item, url: item.url.replace(/^\//, '') })),
  },

  head: [
    ['meta', { name: 'author', content: 'Manoj Mallick' }],
    [
      'meta',
      {
        name: 'keywords',
        content:
          'ai request runtime, ai inspector, llm runtime, pii redaction, pii guard, llm audit, ai observability, ai reliability, retry fallback, cost intelligence, cost governance, budget policy, policy packs, tool runtime, openai, anthropic, gavio',
      },
    ],
    ['meta', { property: 'og:type', content: 'website' }],
    ['meta', { property: 'og:site_name', content: 'Gavio' }],
    ['meta', { property: 'og:title', content: 'Gavio — AI request runtime and inspector' }],
    [
      'meta',
      {
        property: 'og:description',
        content:
          'PII protection, audit trails, reliability, cost intelligence, cost governance, policy packs, and tool runtime checks as composable interceptors.',
      },
    ],
    ['meta', { property: 'og:url', content: HOSTNAME + '/' }],
    ['meta', { name: 'twitter:card', content: 'summary_large_image' }],
    ['meta', { name: 'twitter:title', content: 'Gavio — AI request runtime and inspector' }],
    [
      'meta',
      {
        name: 'twitter:description',
        content:
          'PII protection, audit, reliability, cost intelligence, cost governance, policy packs, and tool runtime checks across Python, Java, and JavaScript.',
      },
    ],
    ['link', { rel: 'canonical', href: HOSTNAME + '/' }],
  ],

  themeConfig: {
    siteTitle: 'gavio',

    nav: [
      { text: 'Docs', link: '/guide/getting-started', activeMatch: '/guide/' },
      {
        text: 'v1.3.0',
        items: [
          { text: 'Changelog', link: 'https://github.com/manojmallick/gavio/blob/main/CHANGELOG.md' },
          { text: 'Releasing', link: 'https://github.com/manojmallick/gavio/blob/main/RELEASING.md' },
        ],
      },
      { text: 'GitHub', link: 'https://github.com/manojmallick/gavio' },
    ],

    sidebar: [
      {
        text: 'Getting started',
        items: [
          { text: 'Quickstart', link: '/guide/getting-started' },
          { text: 'Architecture', link: '/guide/architecture' },
          { text: 'Interceptors', link: '/guide/interceptors' },
          { text: 'Inspector', link: '/guide/inspector' },
          { text: 'Runtime Events', link: '/guide/runtime-events' },
          { text: 'Integrations', link: '/guide/integrations' },
          { text: 'Stability', link: '/guide/stability' },
        ],
      },
      {
        text: 'SDKs',
        items: [
          { text: 'Python', link: '/guide/python' },
          { text: 'JavaScript / TypeScript', link: '/guide/javascript' },
          { text: 'Java', link: '/guide/java' },
        ],
      },
      {
        text: 'Reference',
        items: [
          { text: 'Spec (JSON Schema)', link: 'https://github.com/manojmallick/gavio/tree/main/spec' },
          { text: 'Test vectors', link: 'https://github.com/manojmallick/gavio/tree/main/test-vectors' },
          { text: 'Examples', link: 'https://github.com/manojmallick/gavio/tree/main/examples' },
        ],
      },
    ],

    socialLinks: [
      { icon: 'github', link: 'https://github.com/manojmallick/gavio' },
    ],

    editLink: {
      pattern: 'https://github.com/manojmallick/gavio/edit/main/docs-vp/:path',
      text: 'Edit this page on GitHub',
    },

    search: { provider: 'local' },

    footer: {
      message: 'Released under the MIT License.',
      copyright:
        'MIT © 2026 <a href="https://github.com/manojmallick">Manoj Mallick</a> · Made in Amsterdam 🇳🇱',
    },
  },
})
