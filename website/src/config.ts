/**
 * Site-wide configuration. All external URLs and contact info live here so
 * production overrides and rebrand changes happen in one place.
 *
 * `WORKSHOP_URL` reads from `VITE_WORKSHOP_URL` at build time and falls back
 * to the local dev port so `npm run dev` works out of the box.
 */

export const CONTACT_EMAIL = 'hello@heirloom.dev'
export const GITHUB_URL = 'https://github.com/0xnicholas/heirloom'

export const WORKSHOP_URL: string =
  import.meta.env.VITE_WORKSHOP_URL ?? 'http://localhost:5200/'

/** Build a `mailto:` link for CONTACT_EMAIL, with optional subject. */
export function contactMailto(subject?: string): string {
  const params = subject ? `?subject=${encodeURIComponent(subject)}` : ''
  return `mailto:${CONTACT_EMAIL}${params}`
}
