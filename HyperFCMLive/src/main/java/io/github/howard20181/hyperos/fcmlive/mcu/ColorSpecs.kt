/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use
 * this file except in compliance with the License. You may obtain a copy of the
 * License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 *
 * ----
 *
 * Vendored subset of Google material-color-utilities (Apache-2.0):
 * https://github.com/material-foundation/material-color-utilities
 */
package io.github.howard20181.hyperos.fcmlive.mcu

/**
 * The specs this port publishes, and the one place a spec version is turned into a spec object.
 *
 * Upstream keeps three specifications (2021, 2025 and 2026) and always constructs roles from the
 * newest one, because every later spec layers on top of the earlier ones rather than replacing
 * them: `ColorSpec2025.primary()` takes the 2021 definition and hands it to
 * [DynamicColor.Builder.extendSpecVersion] together with the version it was introduced in, so a
 * single color object resolves to the right definition for whichever scheme asks.
 *
 * This port publishes 2021, 2025 and 2026 — the first two are what the spec menu offers every
 * style, the third is the CMF style's own layer — so the newest spec is [SPEC_2026] and it plays
 * the role upstream gives its newest.
 *
 * Two ways in, mirroring upstream:
 *  - **Building a role** (which palette, which tone function, which background) goes through
 *    [SPEC_2025], and the resulting [DynamicColor] then picks its own definition per scheme.
 *  - **Resolving a role** (tone after the contrast curve, HCT from palette + tone) goes through
 *    [get] with the scheme's own version, which is what [DynamicColor.getTone] and
 *    [DynamicColor.getHct] do.
 */
internal object ColorSpecs {

    /** The 2021 Material You spec. */
    val SPEC_2021: ColorSpec2021 = ColorSpec2021()

    /** The 2025 Material 3 Expressive spec, layered on top of [SPEC_2021]. */
    val SPEC_2025: ColorSpec2025 = ColorSpec2025()

    /** The 2026 spec, layered on top of [SPEC_2025] — the CMF style's own rules. */
    val SPEC_2026: ColorSpec2026 = ColorSpec2026()

    /**
     * The spec that builds roles. Upstream uses its newest spec here for every scheme, so that is
     * what this does too.
     */
    val newest: ColorSpec2021 = SPEC_2026

    /** The spec that resolves values for a scheme of the given version. */
    fun get(specVersion: Scheme.Spec): ColorSpec2021 = when (specVersion) {
        Scheme.Spec.SPEC_2026 -> SPEC_2026
        Scheme.Spec.SPEC_2025 -> SPEC_2025
        Scheme.Spec.SPEC_2021 -> SPEC_2021
    }
}
