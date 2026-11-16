# `mcu/` — vendored Material Color Utilities

This package is a **port of Google's [`material-color-utilities`](https://github.com/material-foundation/material-color-utilities)** (the HCT colour space, `DynamicScheme`, `TonalPalette`, the 2021 and 2025 colour specs, and the scheme variants). Each file keeps its original `Copyright Google LLC` / Apache-2.0 header — that is deliberate: it marks the file as **vendored, not app code**.

## Do not hand-edit these files as if they were ours

They implement a fixed colour algorithm; "tidying" them risks silently diverging from the reference the whole theme is calibrated against. Change them only when deliberately syncing an upstream MCU revision, and treat that as a spec update, not a refactor.

## Safety net: `McuColorTest`

`src/test/.../mcu/McuColorTest.kt` locks the derivation against reference values across `4 seeds × 9 styles × 2 specs × light/dark`. Any change here must keep that test green — run `./gradlew :HyperFCMLive:testDebugUnitTest` and check it before committing an `mcu/` edit.

## How it fits the app

- The **seed** is *not* computed here — `ThemeEngine` takes it from the platform Material You accent (`android.R.color.system_accent1_500`), or the user's own seed when dynamic colour is off.
- This package does the **derivation**: `Scheme.create(seed, variant, dark, spec)` expands that seed into the full role set for the chosen palette style and colour spec — the knobs Compose's built-in `dynamicColorScheme()` does not expose.
- The result (`AppPalette`) is mapped verbatim onto `ColorScheme` by `theme/ComposeTheme.kt`.
