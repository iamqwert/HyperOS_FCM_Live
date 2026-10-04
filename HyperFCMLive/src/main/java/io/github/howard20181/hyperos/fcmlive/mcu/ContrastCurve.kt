/*
 * Copyright 2023 Google LLC
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
 * A value that changes with the contrast level.
 *
 * Usually represents the contrast requirements for a dynamic color on its background. The four
 * values correspond to contrast levels -1.0, 0.0, 0.5 and 1.0 respectively.
 */
internal class ContrastCurve(
    /** Value for contrast level -1.0 */
    private val low: Double,
    /** Value for contrast level 0.0 */
    private val normal: Double,
    /** Value for contrast level 0.5 */
    private val medium: Double,
    /** Value for contrast level 1.0 */
    private val high: Double
) {

    /** Returns the value at the given contrast level. */
    fun get(contrastLevel: Double): Double = when {
        contrastLevel <= -1.0 -> low
        contrastLevel < 0.0 -> MathUtils.lerp(low, normal, (contrastLevel + 1.0) / 1.0)
        contrastLevel < 0.5 -> MathUtils.lerp(normal, medium, (contrastLevel - 0.0) / 0.5)
        contrastLevel < 1.0 -> MathUtils.lerp(medium, high, (contrastLevel - 0.5) / 0.5)
        else -> high
    }
}
