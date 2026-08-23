# UI And Accessibility Design

Last reviewed: August 23, 2026

This document is the source of truth for Airlock's visual hierarchy,
interaction styling, accessibility baseline, and system-area behavior. Product
roles and exact voice live in `PRODUCT_LANGUAGE.md`; reusable implementation
belongs in `UiStyle.java`.

## Design Intent

Airlock should feel playful but operational. The dark interface reduces
the previous white/blue wall, while green identifies normal forward actions,
amber identifies attention, and red identifies requirements or destructive
actions. A user must be able to understand readiness, errors, and enforcement
without relying on color, animation, or Goose jokes.

The app uses platform Java `View` widgets. Material guidance informs hierarchy
and accessibility, but the app does not add AndroidX, Material Components,
Compose, Kotlin, or third-party runtime dependencies.

## Screen Contracts

### Required Android Access

- This is a dedicated first-run and recovery screen, not a dismissible dialog.
- Usage Access, Display Over Other Apps, and visible silent notifications each
  show numbered purpose copy, explicit `DONE` or `NOT DONE` text, and one action.
- The dashboard remains hidden until all three are ready. Revoking any one and
  returning to Airlock shows this screen again without deleting saved setup.
- Battery restriction is a dashboard reliability warning, not one of the three
  portable Android permission gates.

### Dashboard

Keep this top-to-bottom order:

1. Product title, short tagline, and decorative Goose banner.
2. Goose Duty control with the first missing prerequisite named in text.
3. Today's usage for guarded apps only, including explicit off, paused, and
   unhealthy enforcement copy for apps already over their limits.
4. Actual monitoring health and diagnostics.
5. Keyholder number.
6. Master PIN.
7. Guarded apps and limits.
8. Emergency day pass.
9. Android access review, battery warning, and privacy link.

Settings with valid saved values should collapse to status summaries. Do not
turn every section into a permanently expanded form. Starting or stopping Duty
and weakening active configuration must keep the current master-PIN gates.

### App Limit Wizard

- Preserve the two steps: select one or more eligible apps, then assign one
  daily limit to that selection.
- Selection uses a checkbox, selected outline/surface, visible text badge, and
  accessibility state. Color alone is insufficient.
- Loading, retry, empty, search-empty, Usage-Access-required, and active-Duty
  locked states must remain explicit.
- Search text, selection, wizard step, and typed minutes survive configuration
  recreation. Active-Duty edit authorization remains process-local and expires
  after the documented background grace period.

### Master PIN Prompts

- Setup and replacement require exactly four numeric digits other than `0000`.
  The setup helper explains that the Keyholder uses this same PIN for ordinary
  approvals.
- Authorization remains a compact platform dialog with a clear title, short
  reason, one secure numeric field, and Cancel/Continue actions.
- Do not duplicate the dashboard's full PIN setup form inside an authorization
  prompt. Invalid PINs stay in the dialog and use the field error state.

### Blocking Overlay

- The blocker is serious enough to interrupt use, but it is one focused card,
  not a dense settings screen.
- Order remains: app/usage summary, requested minutes, `Text the Keyholder!`,
  approval code, `Loose the Goose!`, optional emergency help, and `Leave App!`.
- The overlay never opens the keyboard automatically. Explicitly tapping an
  input may focus it; rebuilding or restoring the overlay must focus the root
  and keep the keyboard hidden.
- Both numeric fields, current validation text, last requested amount, and the
  emergency disclosure survive temporary hide/reopen for the same package.
- Requested minutes stay editable. Each generated request persists its own
  minutes and expiry, so several approvals may be in flight and redemption
  never reads the current form value.
- Ordinary request and approval values render as exactly four digits, including
  leading zeroes. The shared approval field must continue to allow eight digits
  when the user explicitly opens emergency access.
- Empty or invalid codes show inline text and keep the blocker visible.
- Back with the keyboard closed performs the same safe exit as `Leave App!`.
  Recents, Home, and system navigation must never remain covered by a stale
  blocker. The overlay must remain clear of the navigation inset.
- Success states state the exact granted minutes. The celebration is
  decorative and must be skipped cleanly when system animations are disabled.

## Styling System

`UiStyle` owns all reusable visual decisions:

- Background, surface, text, consolidated green, warning, danger, outline, and
  disabled color roles.
- Screen and section typography.
- Cards, badges, status boxes, selectable rows, and usage rows.
- `primaryButton`, `secondaryButton`, `dangerButton`, `quietButton`, and
  `overlaySecondaryButton`.
- Standard and overlay inputs, spacing helpers, and responsive content widths.
- Activity and overlay insets, cutout handling, system-bar icon appearance, and
  overlay system-bar scrims.

Do not add a raw green, screen padding, radius, field treatment, or button
background in an activity or service. Add or revise a clearly named role in
`UiStyle`, then use it consistently. Goose illustration colors may remain in
the custom decorative views; shared product/status colors must come from
`UiStyle`. XML theme colors must match the corresponding Java token.

Button hierarchy is semantic:

- Primary: the one main forward or completion action in a local flow.
- Secondary: setup or alternate action with less emphasis.
- Danger: stopping, emergency activation, or another consequential action.
- Quiet: disclosure, privacy, back, hide, or low-emphasis utility action.

Adjacent actions always use the shared vertical margins. Do not visually join
filled buttons or invert primary green buttons to green text on a light field;
the established treatment is light text on a filled green surface.

## Measured Accessibility Baseline

Current shared color pairings were checked with the WCAG relative-luminance
formula:

| Pair | Contrast |
| --- | --- |
| Light button text / primary green | 4.90:1 |
| Light button text / deep green | 6.67:1 |
| Body text / card surface | 10.47:1 |
| Muted text / app background | 7.38:1 |
| Ready text / ready surface | 6.77:1 |
| Danger text / danger surface | 5.85:1 |
| Warning text / warning surface | 7.17:1 |
| Strong input outline / input surface | 3.41:1 |

The low-emphasis card outline is decorative; do not use it as the only
boundary for a control or selected state. Disabled controls need not meet
ordinary active-control contrast, but their disabled state must also be
communicated by Android's enabled semantics.

All shared buttons are at least 52 dp high and 48 dp wide. Inputs are at least
52 dp high; overlay inputs are 54 dp. New interactive controls must retain at
least a 48 x 48 dp focusable target and must not be packed so closely that they
read as one action.

Screen forms use visible labels plus hints for every editable field and connect
labels with `setLabelFor`. A compact authorization dialog instead uses its
descriptive title/message and field hint. Use live regions or explicit
announcements for validation and success state changes. Decorative Goose views,
app icons, status-bar scrims, and duplicate selection indicators stay out of
the accessibility tree.

Status and selection always combine text with color and shape. User-visible
copy belongs in `strings.xml`; do not assemble English sentences in Java. Text
must wrap under large font scaling, and all core actions must remain reachable
through scrolling.

## Insets And Responsive Layout

Android 15+ enforces edge-to-edge for this target SDK. Every Activity screen
therefore uses `UiStyle.screenScroll`, `applyScreenInsetsPadding`, and
`attachScreenContent`. The shared listener combines system-window and display-
cutout safe insets with base content padding. Content is constrained to 720 dp
on wide screens instead of stretching forms across the display.

Overlay windows use `TYPE_APPLICATION_OVERLAY`,
`LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES`, `overlayWindowRoot`, and
`attachOverlayContent`. The overlay root provides cutout/system-bar scrims and
constrains content to 600 dp. Do not hardcode status-bar, camera-cutout, gesture,
or three-button navigation dimensions.

## Current Evidence And Gaps

- Static/JVM checks and the focused gesture/three-button navigation smoke path
  cover shared behavior, inset clearance, Recents/Home escape, and Back escape.
- Broad portrait UI smoke coverage exists, but its Android 17 rotation path has
  a known harness timing failure documented in `PROJECT_STATUS.md` and
  `TEST_AUTOMATION_TODO.md`.
- Large-font, landscape, tablet, TalkBack reading order, heading navigation,
  and physical-device cutout behavior still require the release matrix.
- Section titles are visually clear but are not yet exposed as accessibility
  headings. Treat that as accessibility debt rather than claiming complete
  TalkBack semantics.

## Official Guidance

- Android edge-to-edge Views and inset handling:
  <https://developer.android.com/develop/ui/views/layout/edge-to-edge>
- Android display cutout guidance:
  <https://developer.android.com/develop/ui/views/layout/display-cutout>
- Android accessibility for Views, including 48 dp targets:
  <https://developer.android.com/guide/topics/ui/accessibility/views/apps-views>
- Android accessibility principles for labels, state, and custom views:
  <https://developer.android.com/guide/topics/ui/accessibility/views/principles-views>
- Android color and semantic consistency:
  <https://developer.android.com/design/ui/mobile/guides/styles/color>
- Material 3 button hierarchy reference:
  <https://m3.material.io/components/buttons/overview>
- Material 3 text-field reference:
  <https://m3.material.io/components/text-fields/overview>
