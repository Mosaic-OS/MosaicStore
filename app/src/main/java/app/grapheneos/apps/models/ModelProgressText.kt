/*
 * Copyright (C) 2026 The MosaicOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package app.grapheneos.apps.models

import android.content.Context
import android.icu.text.MeasureFormat
import android.icu.util.MeasureUnit
import android.text.BidiFormatter
import android.text.TextDirectionHeuristics
import app.grapheneos.apps.R
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat

internal object ModelProgressText {
    fun format(context: Context, state: String, bytes: Long, total: Long): String {
        if (total <= 0) return state
        var unitIndex = 0
        var divisor = 1L
        while (unitIndex < units.lastIndex && total / divisor >= 1000) {
            divisor *= 1000
            unitIndex++
        }
        val locale = context.resources.configuration.locales[0]
        val number = NumberFormat.getNumberInstance(locale).apply {
            isGroupingUsed = false
            minimumFractionDigits = 0
            maximumFractionDigits = if (unitIndex == 0) 0 else 2
            roundingMode = RoundingMode.HALF_UP
        }
        val scale = BigDecimal.valueOf(divisor)
        val done = number.format(BigDecimal.valueOf(bytes.coerceIn(0, total)).divide(scale))
        val expected = number.format(BigDecimal.valueOf(total).divide(scale))
        val (measure, fallback) = units[unitIndex]
        val unit = MeasureFormat.getInstance(locale, MeasureFormat.FormatWidth.NARROW)
            .getUnitDisplayName(measure) ?: fallback
        val progress = BidiFormatter.getInstance(locale)
            .unicodeWrap("$done/$expected$unit", TextDirectionHeuristics.LTR)
        return context.getString(R.string.models_progress, state, progress)
    }

    private val units = arrayOf(
        MeasureUnit.BYTE to "B",
        MeasureUnit.KILOBYTE to "kB",
        MeasureUnit.MEGABYTE to "MB",
        MeasureUnit.GIGABYTE to "GB",
        MeasureUnit.TERABYTE to "TB",
        MeasureUnit.PETABYTE to "PB",
    )
}
