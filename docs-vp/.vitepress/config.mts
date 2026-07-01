import { defineConfig } from 'vitepress'

export default defineConfig({
  base: '/',
  title: 'Gavio',
  description:
    'The open standard AI gateway for production systems. PII protection, audit trails, reliability, and cost control as composable interceptors — same API in Python, Java, and JavaScript.',

  appearance: 'dark',
  cleanUrls: true,
  lastUpdated: true,

  head: [
    ['meta', { property: 'og:site_name', content: 'Gavio' }],
    ['meta', { name: 'twitter:card', content: 'summary_large_image' }],
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
