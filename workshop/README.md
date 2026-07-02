# Heirloom Workshop

Frontend for the Heirloom ontology modeling workbench. Built with
**Vite 8 + React 19 + TypeScript + Mantine v9**.

## Development

```bash
cd workshop
npm install
npm run dev      # http://localhost:5200
```

## Build

```bash
npm run build         # tsc -b + vite build
npm run preview       # preview production bundle
```

## Lint / Test

```bash
npm run lint          # ESLint flat config
npx vitest run        # 77 tests across 14 files
```

## Architecture

```
src/
├── api/                    # TanStack Query hooks (no UI deps)
│   ├── query.ts            #   executeQuery()
│   ├── client.ts           #   ApiClient interface (currently dead code)
│   └── mock/               # MSW handlers + localStorage-backed store
├── components/
│   ├── layout/             # AppLayout (AppShell) + TopBar + SideNav + QueryConsole
│   ├── schema/             # TypeList/FieldTable/TypeEditor + 3 others
│   ├── security/           # RoleList/ActionList/RoleEditor/ActionEditor
│   ├── query/              # QueryHistory/QueryEditor/QueryResults
│   ├── stats/              # MetricCards/OntologyGraph + 3 others
│   ├── shared/             # ValidationBar/PlaceholderPage
│   ├── theme/              # (empty — Mantine handles theming)
│   └── providers/          # AppProviders (Mantine + Modals + Notifications + Router)
├── hooks/                  # 6 data hooks (useSchemaRegistry/useQueries/etc.)
├── lib/
│   ├── types.ts            # Domain model: ResourceType/QueryDSL/Role/Action
│   ├── constants.ts        # ABILITIES/FIELD_TYPES/QUERY_TEMPLATES + 3 migrated from config.ts
│   ├── theme.ts            # Mantine createTheme (indigo/stone palette)
│   ├── notifications.ts    # notifyError/Success/Info helpers
│   └── validation/         # 4 validators + tests (untouched by migration)
├── pages/                  # 14 routes
├── mocks/browser.ts        # MSW worker (mock mode bootstrap)
├── test/setup.ts           # jsdom polyfills (localStorage/matchMedia/ResizeObserver)
├── main.tsx                # bootstrap → AppProviders
└── index.css               # Mantine CSS imports (no Tailwind)
```

## UI: Mantine v9

- **Provider**: `AppProviders` wraps `MantineProvider` + `ModalsProvider` + `Notifications` + `QueryClient` + `BrowserRouter`.
- **Theme**: `src/lib/theme.ts` defines `indigo` (primary) and `stone` (neutral) `MantineColorsTuple`. Auto dark mode via `defaultColorScheme="auto"`.
- **Forms**: `@mantine/form` for `TypeEditor`/`RoleEditor`/`ActionEditor` (replaces 4–6 useState each).
- **Modals**: `@mantine/modals` is set up; `modals.openConfirmModal()` is the preferred pattern for new confirm dialogs.
- **Toasts**: `@mantine/notifications` + `src/lib/notifications.ts` helpers.
- **Icons**: `@tabler/icons-react` (no inline SVGs in source).

## Test Environment

jsdom 27+ missing some browser APIs; polyfilled in `src/test/setup.ts`:
- `window.localStorage` (Mantine hooks + tests)
- `window.matchMedia` (Mantine color scheme)
- `window.ResizeObserver` (Mantine Select / Popover)
- `document.fonts` (Mantine Textarea autosize — disabled by using fixed `rows={N}`)

`vitest` runs with a 15s timeout for pages using `Select` (parallel test load can slow Mantine popper rendering).

## Configuration

- `VITE_API_MODE=mock` (default in `.env.mock`): MSW intercepts `/api/*`.
- `VITE_API_MODE=real`: real backend; ensure routes match `src/api/query.ts`.

## Notable Tradeoffs

- **No Tailwind**: UI uses Mantine primitives only. `src/index.css` imports only Mantine CSS.
- **No TanStack Table**: only used to be imported but never actually used; removed in cleanup.
- **No `@lucide/react`**: Workshop never used lucide; all icons are `@tabler/icons-react`.
- **No Phase 9 code-splitting**: Bundle is 233KB JS gzip. `npm run build` warns about 500KB+ chunk; defer code-splitting until needed.
