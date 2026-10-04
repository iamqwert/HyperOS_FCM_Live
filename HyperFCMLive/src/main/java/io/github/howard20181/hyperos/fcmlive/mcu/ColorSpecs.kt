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
 * This port publishes 2021 and 2025 — the two the app offers — so the newest spec is
 * [SPEC_2025] and it plays the role upstream gives its newest.
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

    /**
     * The spec that builds roles. Upstream uses its newest spec here for every scheme, so that is
     * what this does too.
     */
    val newest: ColorSpec2021 = SPEC_2025

    /** The spec that resolves values for a scheme of the given version. */
    fun get(specVersion: Scheme.Spec): ColorSpec2021 =
        if (specVersion == Scheme.Spec.SPEC_2025) SPEC_2025 else SPEC_2021
}
