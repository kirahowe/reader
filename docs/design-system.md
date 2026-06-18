# Design system

How Reader's UI is built, and how to add to it without it drifting.
The look is **"Reading Room"**: warm paper, serif headlines, a single
deep-green accent, sharp modern corners, and a calm editorial feel.

The system is small and deliberately plain — server-rendered Hiccup,
two stylesheets, no build step, no utility classes, no framework. New
screens are assembled from existing primitives, so they fit the rest of
the app by construction.

## Where things live

| Concern | File |
| --- | --- |
| Design tokens (colour, type, spacing, radii) | `resources/public/css/tokens.css` |
| The stylesheet / component styles | `resources/public/css/main.css` |
| Hiccup primitives (buttons, chips, cards, fields, icons) | `src/reader/ui/components.clj` |
| The app shell + page wrappers | `src/reader/ui/layout.clj` |
| One namespace per screen | `src/reader/ui/pages/*.clj` |

Every CSS rule is keyed to a **semantic class** that matches a primitive
in `components.clj` (`.btn`, `.chip`, `.card`, `.field`, …). There are no
utility classes — to change how something looks, edit its one rule in
`main.css`; to use it, reach for the primitive.

`main.css` is organised in layers, top to bottom: **base** (reset,
elements) → **app shell** (`.topbar`, `<main>`) → **primitives**
(`.btn`, `.chip`, `.card`, `.page-head`, `.field`, `.backnav`) →
**views** (`.readables`, `.reader`, `.login`, …).

## Tokens

Colour, type, space, and radius are all CSS custom properties in
`tokens.css`, redefined under `@media (prefers-color-scheme: dark)` so
light and dark stay in lockstep. Reference tokens, never raw values.

- **Colour** — `--paper`/`--paper-2` (surfaces), `--ink`/`--ink-soft`
  (text), `--muted`, `--rule`/`--rule-2` (borders), `--accent`
  (+`--accent-2`, `--accent-tint`), `--gold` (transient/attention states).
- **Type** — `--font-ui` (Inter), `--font-prose` (serif, for headlines
  and reading). The accent green is the only brand colour; gold is
  reserved for in-progress/error states.
- **Space** — `--s-1`…`--s-7` on a consistent scale.
- **Radius** — `--r-sm`/`--r-md`/`--r-lg`, all small and sharp. No pills.

## Primitives

All return plain Hiccup from `reader.ui.components` (aliased `c`):

| Primitive | Call | Notes |
| --- | --- | --- |
| Button | `(c/button {:type "submit" :variant :primary} "Save")` | `:variant` ∈ `:primary` `:icon` `:link`, or omit for the quiet secondary |
| Ghost link | `(c/link-button href "Add manually")` | an `<a>` styled as a quiet button |
| Chip | `(c/chip :reading "reading")` | status pill; variant keyword → `chip--<variant>` |
| Card | `(c/card …)` | a raised surface (`<section class="card">`) |
| Page head | `(c/page-head "Title" subtitle action)` | title + optional subtitle + optional right-aligned action |
| Back link | `(c/back-link "/")` | the sub-page back affordance |
| Fields | `(c/text-field …)`, `(c/select-field …)`, `(c/textarea-field …)` | a labelled control with an optional error |
| Icons | `c/icon-book`, `c/icon-trash`, `c/icon-chevron-left` | 24×24, `currentColor`, sized by CSS |
| Byline | `(c/byline authors)` | comma-separated author links |

## Page anatomy

A signed-in screen is wrapped by `layout/app-page`, which renders the
shared shell (top bar with wordmark + primary nav) and the active-section
highlight, then drops your content into `<main>`:

```clojure
(layout/app-page
 "Sources" :sources            ; <title> + active nav key
 (list
  (c/page-head "Sources")
  (into [:ul.entities] …)))
```

`:login`-style standalone pages (no shell) use `layout/page` directly.
`content` is a **seq** of sibling forms (Hiccup renders each as a child
of `<main>`).

## Adding a new screen — the recipe

1. Add a `reader.ui.pages.<name>` namespace; require `components :as c`
   and `layout`.
2. Build the body from primitives, wrapped in `(layout/app-page title
   active-key (list …))`. Add the section to the top-nav in
   `layout/topbar` if it deserves a home there.
3. Need a new control? Add a primitive to `components.clj` **and** its one
   rule to the matching layer in `main.css` — don't inline styles or
   hand-roll classes on a page. Add it to the styleguide too.

## Conventions

- **Semantic HTML, always.** `header`/`nav`/`main`/`article`/`section`,
  real headings, real `<button>`s and `<label>`s. The markup should read
  cleanly with no stylesheet attached.
- **No utility classes, no inline styles.** One semantic class per
  primitive; variants are `block--modifier`.
- **Light and dark are equals.** Anything you add to `tokens.css` gets a
  dark value in the same commit.
- **Accent is precious.** Green for primary/active/links; gold only for
  in-progress and error states; everything else is paper and ink.

## Third-party widgets

The sign-in form is Hanko's `<hanko-auth>` web component (a shadow-DOM
island). It's themed two ways in `main.css`: Hanko's CSS custom
properties — mapped onto our tokens so it inherits the palette — and a
few `::part()` rules to sharpen its controls. See
<https://docs.hanko.io/guides/hanko-elements/customize-appearance>.
