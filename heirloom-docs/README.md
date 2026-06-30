# Heirloom Docs

Public documentation for Heirloom, built with [Mintlify](https://mintlify.com).

## Local development

Requires Node.js v20.17.0 or higher.

```bash
cd heirloom-docs
npx mint dev
```

Open http://localhost:3000 to preview.

## Checks

```bash
# Validate navigation and links
npx mint broken-links
```

## Deployment

This directory is configured as a standalone Mintlify documentation site. To deploy, connect the `heirloom-docs/` folder to a Mintlify project and push changes.
