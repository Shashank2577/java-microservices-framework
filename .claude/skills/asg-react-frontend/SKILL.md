---
name: asg-react-frontend
description: Use whenever working on the React/TypeScript frontend for ASG Edge Plus — components, Redux slices, tenant switching, JWT handling, the X-Client-Context header, approval workflow UI, lease entry forms, PDF viewer integration, or any frontend code that crosses the API boundary. This is the ONLY frontend skill; for general React/TypeScript patterns invoke `vercel:react-best-practices`. This skill encodes ASG-specific opinions only.
---

# ASG React Frontend — Project-Specific Conventions

This skill is a **thin wrapper**. For general React, TypeScript, and component patterns invoke **`vercel:react-best-practices`** (and `vercel:shadcn` for shadcn/ui patterns, `vercel:ai-sdk` if AI features land later). Those skills carry hundreds of pages of best practice — don't duplicate.

**This skill covers what is ASG-specific.** Read the Vercel skills first; come here for the deltas.

> **Read first:** ADR-002 (React + TS + Redux Toolkit), ADR-005 (JWT dual-token), `asg-decimal-precision` (display rules), `asg-contract-domain` (entities the UI renders).

## 1. The Stack — and What We Are *Not*

| Concern | Choice | Why |
| ------- | ------ | --- |
| Framework | **React + TypeScript SPA** | Per ADR-002. |
| State | **Redux Toolkit** (RTK + RTK Query) | Per ADR-002. Predictable async state for complex workflows. |
| Routing | React Router v6+ | SPA; no server-side routing. |
| Build | **Vite** | Fast, sane defaults; no opinion on bundler beyond this. |
| Styling | TBD per design phase | Likely Tailwind + a component library. Not load-bearing here. |
| Forms | React Hook Form + Zod | Schema-driven, plays well with RTK. |
| Testing | **React Testing Library + MSW** | Component + integration. **No Cypress mandate** yet. |
| HTTP | RTK Query (fetchBaseQuery) | Single layer; do not add Axios alongside. |

### We are NOT Next.js

ADR-002 chose React + Redux Toolkit + **SPA**. We are **not** using Next.js — no App Router, no server components, no `/app` directory, no `getServerSideProps`, no middleware-on-the-edge.

> When invoking `vercel:react-best-practices`, **ignore App-Router-specific guidance**. The product is a single-page application served behind a CDN; routing is client-side; data is fetched via RTK Query against the API Gateway.

If you find yourself reaching for `next/*`, you are on the wrong track. Stop and re-read this section.

## 2. Project Layout

```
src/
├── app/
│   ├── store.ts                    # configureStore — RTK
│   ├── apiClient.ts                # RTK Query base — header injection lives here
│   └── router.tsx                  # React Router routes
├── features/                       # one folder per business domain — 33-component aligned
│   ├── auth/                       # login, session, dual-token plumbing
│   ├── tenant/                     # tenant switcher, active-tenant state
│   ├── contracts/                  # ContractList, ContractDetail, Modification UI
│   ├── payments/                   # Payment entry + approval queue
│   ├── asc842/                     # Lease schedules, period close views
│   ├── approvals/                  # My Tasks, approval decision UIs
│   ├── documents/                  # Upload, PDF viewer wrapper
│   ├── masters/                    # Vendors, picklists
│   ├── workflows/                  # Workflow definition + execution views
│   ├── reports/                    # Dashboards, exports
│   └── ...                         # one per backend domain
├── shared/
│   ├── components/                 # design-system primitives
│   ├── hooks/                      # cross-feature hooks
│   ├── ui/                         # cross-feature compositional UI (forms, tables)
│   └── lib/                        # money formatter, date utils, decimal utils
└── types/                          # generated TS types from OpenAPI
```

Feature folders mirror the 33-component map (`asg-edge-plus-kb/_index/component-map.md`). One Redux slice per feature. Cross-feature imports go through `shared/`, never feature-to-feature.

## 3. Redux Toolkit Slice Conventions

```ts
// features/contracts/contractsSlice.ts
import { createSlice, PayloadAction } from '@reduxjs/toolkit';

interface ContractsState {
  selectedContractId: string | null;
  draft: ContractDraft | null;
  filters: ContractFilters;
}

const initialState: ContractsState = {
  selectedContractId: null,
  draft: null,
  filters: { status: 'ACTIVE' },
};

const contractsSlice = createSlice({
  name: 'contracts',
  initialState,
  reducers: {
    contractSelected: (s, a: PayloadAction<string>) => { s.selectedContractId = a.payload; },
    draftStarted:     (s, a: PayloadAction<ContractDraft>) => { s.draft = a.payload; },
    draftCleared:     (s) => { s.draft = null; },
    filtersChanged:   (s, a: PayloadAction<ContractFilters>) => { s.filters = a.payload; },
  },
});

export const { contractSelected, draftStarted, draftCleared, filtersChanged } = contractsSlice.actions;
export default contractsSlice.reducer;
```

### Slice rules
- **One slice per feature folder.** Co-located with the feature.
- **Server data goes in RTK Query**, not in slices. Slices hold *UI* state — selections, drafts, filter toggles, ephemeral form state that survives navigation.
- **Action names are past-tense events.** `contractSelected`, not `selectContract`. Mirror the domain-events convention from the backend.
- **No thunks for fetching.** RTK Query owns fetching. Thunks only for orchestration that can't be expressed as a query/mutation.
- Selectors live in the slice file, exported as `selectContractFilters(state)` etc.

## 4. RTK Query — The API Boundary

```ts
// app/apiClient.ts
import { createApi, fetchBaseQuery, BaseQueryFn } from '@reduxjs/toolkit/query/react';
import { selectActiveTenantSlug } from '../features/tenant/tenantSlice';
import { selectPlatformToken, selectTenantToken } from '../features/auth/authSlice';

const baseQuery = fetchBaseQuery({
  baseUrl: import.meta.env.VITE_API_BASE_URL,
  prepareHeaders: (headers, { getState }) => {
    const state = getState() as RootState;
    const platformToken = selectPlatformToken(state);
    const tenantToken   = selectTenantToken(state);
    const tenantSlug    = selectActiveTenantSlug(state);

    // Per ADR-005 dual-token model:
    if (platformToken) headers.set('Authorization', `Bearer ${platformToken}`);
    if (tenantToken)   headers.set('X-Tenant-Authorization', `Bearer ${tenantToken}`);
    // Per BRD-02 MCT-16/17 — required on every tenant-scoped request:
    if (tenantSlug)    headers.set('X-Client-Context', tenantSlug);
    return headers;
  },
});

export const api = createApi({
  reducerPath: 'api',
  baseQuery,
  tagTypes: ['Contract', 'Payment', 'Approval', 'Document', 'Vendor', 'Workflow'],
  endpoints: () => ({}),
});
```

Feature endpoints inject into this client via `api.injectEndpoints({...})` — one file per feature. Tags drive cache invalidation.

## 5. Dual JWT Token Handling (ADR-005)

Two tokens, two purposes:

| Token | Purpose | Storage | Sent as |
| ----- | ------- | ------- | ------- |
| **Platform token** | Identifies the user across tenants; admin/internal APIs | httpOnly cookie (preferred) or sessionStorage | `Authorization: Bearer ...` |
| **Tenant token** | Authorizes the user for the *currently active* tenant; carries tenant-scoped claims | sessionStorage | `X-Tenant-Authorization: Bearer ...` |

### Tenant switching UX

When the user picks a different tenant from the tenant switcher:

1. UI dispatches `tenantSwitchRequested(slug)`.
2. Thunk hits `POST /auth/tenant-token` with the platform token + target tenant slug.
3. On success, store the new tenant token and update `activeTenantSlug`.
4. **RTK Query cache is invalidated wholesale** — every tag, every cached query. Different tenant means different data; no shared cache.
5. Active route is re-evaluated; if not permitted in the new tenant, redirect to home.

```ts
// features/tenant/switchTenant.ts
export const switchTenant = createAsyncThunk<void, string>(
  'tenant/switch',
  async (slug, { dispatch }) => {
    const { tenantToken } = await tenantTokenApi.fetch(slug);
    dispatch(tenantSet({ slug, token: tenantToken }));
    dispatch(api.util.resetApiState());    // wipe all caches
  }
);
```

### Token refresh

- Platform token refresh: silent via `/auth/refresh` on 401 (RTK Query base reauth middleware).
- Tenant token refresh: re-fetch the active tenant token on 401 *if* the platform token is still valid; otherwise full re-login.

## 6. The `X-Client-Context` Header (BRD-02 MCT-16/17)

**Every tenant-scoped request must carry `X-Client-Context: <tenantSlug>`.** This is the contract with the backend's `MultiTenantContextManager` middleware.

- Injected centrally in `apiClient.ts` (§4) — never per-call.
- The active slug comes from `selectActiveTenantSlug` — Redux is the single source.
- If the slug is empty (e.g., on `/login`), the header is omitted. The backend's tenant-resolution filter must tolerate this on auth-public endpoints.
- **Never** put the tenant slug in the URL path (e.g., `/api/{tenant}/contracts`). The slug is a context header, not a URL parameter. URL-based routing was rejected to keep links shareable across tenants for cross-tenant users.

## 7. Permissions — Frontend Renders, Never Enforces

Per BRD-03 §10.3 the **backend is the only authority** for permissions. The frontend:

- Calls `GET /me/permissions` on session establish + on tenant switch.
- Receives a flat set of permission strings (e.g., `contract.read`, `contract.modify`, `payment.approve.tier_2`).
- Renders UI based on this set — hides buttons, disables actions, shows read-only forms.
- **Does not** evaluate amount-tier rules, SoD rules, or any business logic. Those live in `AuthorizationEngine`. If the user clicks an action, the backend re-checks and may return 403; the UI handles 403 gracefully.

```tsx
// shared/permissions/usePermission.ts
export const usePermission = (perm: string) => {
  const perms = useAppSelector(selectPermissionSet);
  return perms.has(perm);
};

// usage
const canModify = usePermission('contract.modify');
return <Button disabled={!canModify} onClick={openEditor}>Edit</Button>;
```

**Never** hardcode role names in components. Roles change per tenant; permission strings are stable.

## 8. PDF Viewer Component

Per the BRD requirements for contract annotation, the platform needs an in-browser PDF viewer with annotation support. Wrap a chosen library (e.g., react-pdf for viewing, plus a thin annotation overlay) behind one component:

```tsx
// features/documents/PdfAnnotatedViewer.tsx
interface Props {
  documentId: string;
  versionId: string;
  initialAnnotations?: Annotation[];
  readOnly?: boolean;
  onAnnotationChange?: (annotations: Annotation[]) => void;
}
```

- Documents stream from `/documents/{id}/versions/{versionId}/content` (signed URL handed back by the API).
- Annotations are stored as structured JSON on the backend; never as flattened layers on the PDF.
- Per ADR-010 (S3 storage), the viewer must not assume a stable PDF URL — always re-fetch the signed URL when the version is opened.
- All consumers in the codebase use this component; never embed a viewer directly.

> **Open question:** annotation library choice (pdf.js + custom overlay vs. PSPDFKit). Decide before contract-document UI is built.

## 9. Forms — Lease Data Entry

Lease entry forms are the most precision-sensitive surface in the platform.

### Decimal input
- Use a `<MoneyInput>` component, never raw `<input type="number">`. JS numbers lose precision at 4 decimal places.
- The component holds the value as a **string** internally and converts to `Money` on submit.
- Display rounding follows the active currency's default fraction digits; the raw value (scale 4) goes to the API as a JSON string.
- Cross-link: see `asg-decimal-precision` §9 — never send amounts as JSON numbers.

```tsx
<MoneyInput
  name="baseRent"
  currency={contract.currency}
  value={formState.baseRent}
  onChange={(amount) => setField('baseRent', amount)}   // amount: string at scale 4
/>
```

### Date input
- Date pickers default to `LocalDate` (no time, no timezone).
- The commencement date is **load-bearing** — it anchors ASC 842 measurement (per `asg-contract-domain`). Validate it is on or before today + reasonable window; warn (don't block) if it is in the past beyond the open period.
- Always send dates as ISO `yyyy-MM-dd` strings, never `new Date()` (which serializes with TZ and ruins the LocalDate semantics).

### Form validation
- Zod schemas per form, parsed before dispatch. Server errors (RFC 7807 per `java-api-errors`) are merged into the form state on 400.

## 10. Approval Workflow UI — Replacing the "Excel Boomerang"

The platform's marquee UX win is in-app approvals. The pattern:

| Screen | Purpose |
| ------ | ------- |
| **My Tasks** widget on Dashboard | Lists pending approvals across all domains for the current user. Powered by WorkflowEngine. |
| **Approval Detail** | Shows the candidate (payment, ASC 842 entry, contract change), the requesting context, the approval chain so far, and decision controls. |
| **Approval History** | For any business object, the trail of decisions with approver, timestamp, reason. |

### Conventions
- All approval interactions go through the WorkflowEngine API surface, not directly to the source domain. The decision flows back to the AccountingEngine (or originating service) via the protocol in `asg-hub-and-spoke-financial` §3.
- A decision UI must show: who submitted, when, what the prior steps decided (if any), the full chain, and what happens next on approve/reject/return.
- Optimistic UI is **forbidden** on approvals — wait for the backend confirmation. A wrongly displayed "approved" state is a SOX finding.
- Returned items appear in the submitter's queue with the rejection reason rendered prominently.

## 11. Testing

- **React Testing Library** for components. Test behavior, not implementation.
- **MSW (Mock Service Worker)** for API mocking — at the network layer, not the RTK Query layer. This catches header bugs (the dual token, the `X-Client-Context`).
- **No Cypress mandate** at this stage. If/when added, it's for full E2E smoke against a staging environment, not for routine PR gating.
- Tenant switching, permission gating, and approval flows are the highest-value test surfaces. Cover them first.

```ts
// example MSW handler asserting the platform requires X-Client-Context
http.get('/api/contracts', ({ request }) => {
  const ctx = request.headers.get('X-Client-Context');
  if (!ctx) return new Response(null, { status: 400 });
  return HttpResponse.json(contractFixturesFor(ctx));
});
```

## 12. Build and Bundle

- **Vite** in dev and build. Avoid CRA / Webpack configs unless there is a clear blocker.
- Code-split per feature folder. Each top-level route is a `lazy()` import.
- Environment variables: `VITE_API_BASE_URL`, `VITE_FEATURE_FLAGS_URL`, etc. Never bake secrets.
- The build artifact is a static bundle served from CloudFront (per the project architecture). No Node server at the edge.

## 13. Smells

- An `import` from `next/*` anywhere. Wrong framework.
- A request that omits `X-Client-Context` on a tenant-scoped endpoint.
- A `<input type="number">` on a money field. Use `<MoneyInput>`.
- Amount math in JavaScript (`a + b` where `a` and `b` are amount strings). Convert to a decimal library or — better — do the math on the server.
- Tenant slug in the URL path. Header only.
- Role-name conditional in JSX (`if (user.role === 'admin')`). Use permission strings.
- `Axios` added alongside RTK Query. Pick one; we picked RTK Query.
- A `useEffect`-based fetch that bypasses RTK Query. The cache layer is mandatory.
- Optimistic UI on an approval action. Wait for the server.

## Reference

- **`vercel:react-best-practices`** — invoke for general React + TypeScript patterns, hooks, performance, accessibility.
- **`vercel:shadcn`** — invoke if/when adopting shadcn/ui patterns for the component library.
- **`vercel:ai-sdk`** — invoke if/when AI features (e.g., document summarization) land in the UI.
- ADR-002 (React + TS + RTK), ADR-005 (dual JWT), ADR-010 (S3 documents).
- BRD-02 MCT-16/17 (`X-Client-Context`), BRD-03 §10.3 (permissions are backend-authoritative).
- `asg-decimal-precision` §9 (JSON amount serialization).
- `asg-contract-domain` (entities rendered by lease forms).
- `asg-hub-and-spoke-financial` §3 (approval protocol the UI participates in).
