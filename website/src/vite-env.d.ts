/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** Production override for the Workshop entry point. */
  readonly VITE_WORKSHOP_URL?: string
}
