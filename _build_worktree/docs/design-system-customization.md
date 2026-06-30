# Mem Design System Customization

Mem's design system must be customizable by the user. This is a product feature,
not just a settings nicety. The app should be able to look calm, dense, journal-
like, glassy, minimal, or high-contrast without rewriting screens.

## Product Principle

Customization should be expressive but curated. Users should be able to make Mem
feel like their own private space, while the app still preserves readability,
navigation consistency, accessibility, and a premium native feel.

Avoid a free-form styling system where users can accidentally create unusable
screens. Prefer theme profiles, semantic controls, and constrained ranges.

## User-Facing Controls

MVP appearance controls:

- Theme profile: Default, Focus, Journal, Glass, Library, High Contrast.
- Accent color.
- Light, dark, or system mode.
- Density: comfortable, standard, compact.
- Corner style: soft, standard, sharp.
- Dock style: solid, translucent, glass.
- Feed layout density.
- Library default mode: remembers last selected mode.

Later controls:

- Typography personality.
- Background treatment.
- Motion level.
- Source-card image emphasis.
- Per-space themes for collections/journals.

## Theme Profiles

Default:

- Balanced agent-first memory console.
- Warm white base, ink text, muted teal accent.

Focus:

- Denser, quieter, more command-center-like.
- Reduced decoration and tighter spacing.

Journal:

- More personal, warmer, more timeline-forward.
- Softer surfaces and calmer accent usage.

Glass:

- Floating dock and translucent surfaces emphasized.
- Must preserve contrast and avoid unreadable blur.

Library:

- More visual browsing.
- Stronger thumbnails and feed/grid affordances.

High Contrast:

- Accessibility-first.
- Strong borders, clear focus states, minimal translucency.

## Engineering Rule

No screen should hardcode visual styling directly. Screens compose product
components, and components read from Mem theme tokens.

Use semantic tokens:

- `surface`
- `surfaceRaised`
- `surfaceFloating`
- `surfaceMuted`
- `textPrimary`
- `textSecondary`
- `textTertiary`
- `accent`
- `accentMuted`
- `warning`
- `success`
- `danger`
- `borderSubtle`
- `borderStrong`

Use role tokens:

- `captureAction`
- `agentAction`
- `sourceVideo`
- `sourceArticle`
- `sourceNote`
- `sourceDocument`
- `processing`
- `needsReview`
- `needsAuth`
- `timelineRail`
- `dockBackground`
- `dockSelected`

Use layout tokens:

- spacing scale
- corner scale
- elevation scale
- border width scale
- icon size scale
- minimum touch target
- content density
- feed row image ratio
- source card aspect ratio

Use motion tokens:

- duration
- easing
- blur strength
- press scale
- transition style
- reduce-motion override

## Compose Implementation Shape

Create a `MemTheme` wrapper:

- `MemColorScheme`
- `MemTypography`
- `MemShapes`
- `MemSpacing`
- `MemElevation`
- `MemMotion`
- `MemDensity`
- `MemGlass`

Expose tokens through CompositionLocals, not global constants.

Example component rules:

- `MemFloatingDock` receives navigation state and action callbacks, not colors.
- `MemSourceRow` receives source display data and density, not layout literals.
- `MemCommandField` receives command state and actions, not theme decisions.
- `MemFeedItem` adapts to compact/standard/comfortable density through tokens.

Persist user choices in an `AppearanceSettings` table or DataStore entry. The
theme should update reactively without restarting the app.

## Accessibility Guardrails

Every theme profile must pass:

- Text contrast checks for primary and secondary text.
- Minimum touch target sizes.
- Dynamic type support.
- Reduce motion support.
- Dark mode support or an explicit fallback.
- No content hidden under floating dock.

Glass/translucent themes need special checks:

- Blur never drops text contrast below target.
- Bottom dock has a fallback solid surface when blur is unavailable.
- Content behind the dock fades or pads enough to remain readable.

## Build Implication

During implementation, build the design system before building many screens.
Otherwise the first screens will accidentally lock in one visual style and make
customization expensive later.
