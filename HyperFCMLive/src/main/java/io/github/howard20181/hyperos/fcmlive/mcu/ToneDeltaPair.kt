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

/** Describes the relative relation between the tones of two roles in a [ToneDeltaPair]. */
internal enum class TonePolarity {
    DARKER,
    LIGHTER,
    RELATIVE_DARKER,
    RELATIVE_LIGHTER,

    /** Deprecated upstream; use [DeltaConstraint] instead. */
    NEARER,

    /** Deprecated upstream; use [DeltaConstraint] instead. */
    FARTHER
}

/** Describes how to fulfil a [ToneDeltaPair] constraint. */
internal enum class DeltaConstraint {
    EXACT,
    NEARER,
    FARTHER
}

/**
 * Documents a constraint between two dynamic colors, in which their tones must have a certain
 * distance from each other.
 *
 * Prefer a dynamic color with a background; this is for the special cases where a design wants
 * tonal distance — literally contrast — between two colors that have no background / foreground
 * relationship or contrast guarantee.
 */
internal class ToneDeltaPair(
    /** The first role in a pair. */
    val roleA: DynamicColor,
    /** The second role in a pair. */
    val roleB: DynamicColor,
    /** Required difference between tones. Absolute value; negative values are undefined. */
    val delta: Double,
    /** The relative relation between the tones of [roleA] and [roleB]. */
    val polarity: TonePolarity,
    /**
     * Whether these two roles should stay on the same side of the "awkward zone" (T50-59). Needed
     * for certain cases where one role has two backgrounds.
     */
    val stayTogether: Boolean,
    /** How to fulfil the tone delta pair constraint. */
    val constraint: DeltaConstraint
) {

    /** Upstream constructor with the "stay together" flag; the constraint is [DeltaConstraint.EXACT]. */
    constructor(
        roleA: DynamicColor,
        roleB: DynamicColor,
        delta: Double,
        polarity: TonePolarity,
        stayTogether: Boolean
    ) : this(roleA, roleB, delta, polarity, stayTogether, DeltaConstraint.EXACT)

    /** Upstream constructor with an explicit constraint; "stay together" is always true. */
    constructor(
        roleA: DynamicColor,
        roleB: DynamicColor,
        delta: Double,
        polarity: TonePolarity,
        constraint: DeltaConstraint
    ) : this(roleA, roleB, delta, polarity, true, constraint)
}
