import { defineConfig } from 'vitepress'

// Canonical docs domain — change this to your deployed docs URL.
const HOSTNAME = 'https://gavio.io'

export default defineConfig({
  base: '/',
  lang: 'en-US',
  title: 'Gavio',
  titleTemplate: ':title · Gavio — AI gateway',
  description:
    'The open standard AI gateway for production systems. PII protection, audit trails, reliability, and cost control as composable interceptors — same API in Python, Java, and JavaScript.',

  appearance: 'dark',
  cleanUrls: true,
  lastUpdated: true,

  // Generates /sitemap.xml for search engines.
  sitemap: { hostname: HOSTNAME },

  head: [
    ['meta', { name: 'author', content: 'Manoj Mallick' }],
    [
      'meta',
      {
        name: 'keywords',
        content:
          'ai gateway, llm gateway, pii redaction, pii guard, llm audit, ai observability, ai reliability, retry fallback, openai, anthropic, python ai gateway, java ai gateway, javascript ai gateway, gavio',
      },
    ],
    ['meta', { property: 'og:type', content: 'website' }],
    ['meta', { property: 'og:site_name', content: 'Gavio' }],
    ['meta', { property: 'og:title', content: 'Gavio — the open standard AI gateway' }],
    [
      'meta',
      {
        property: 'og:description',
        content:
          'PII protection, audit trails, reliability, and cost control as composable interceptors. Same API in Python, Java, and JavaScript.',
      },
    ],
    ['meta', { property: 'og:url', content: HOSTNAME + '/' }],
    ['meta', { name: 'twitter:card', content: 'summary_large_image' }],
    ['meta', { name: 'twitter:title', content: 'Gavio — the open standard AI gateway' }],
    [
      'meta',
      {
        name: 'twitter:description',
        content:
          'PII protection, audit, reliability, cost control — one interceptor API in Python, Java, and JavaScript.',
      },
    ],
    ['link', { rel: 'canonical', href: HOSTNAME + '/' }],
  ],

  themeConfig: {
    siteTitle: 'gavio',

    nav: [
      { text: 'Docs', link: '/guide/getting-started', activeMatch: '/guide/' },
      {
        text: 'v0.1.0',
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
      copyright: '© the Gavio project contributors',
    },
  },
})
