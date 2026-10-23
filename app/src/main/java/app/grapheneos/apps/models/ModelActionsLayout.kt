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
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import kotlin.math.max
import kotlin.math.roundToInt

class ModelActionsLayout(context: Context, attrs: AttributeSet) : ViewGroup(context, attrs) {
    private val gap = (8 * resources.displayMetrics.density).roundToInt()
    private var stacked = false

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val available = (MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight).coerceAtLeast(0)
        val groups = (0 until childCount).map(::getChildAt).filter { it.visibility != GONE }
        groups.forEach {
            it.measure(MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        }
        val totalWidth = groups.sumOf { it.measuredWidth } + gap * (groups.size - 1).coerceAtLeast(0)
        stacked = totalWidth > available
        val contentWidth = if (stacked) groups.maxOfOrNull { it.measuredWidth } ?: 0 else totalWidth
        val contentHeight = if (stacked) {
            groups.sumOf { it.measuredHeight } + gap * (groups.size - 1).coerceAtLeast(0)
        } else {
            groups.maxOfOrNull { it.measuredHeight } ?: 0
        }
        setMeasuredDimension(resolveSize(contentWidth + paddingLeft + paddingRight, widthMeasureSpec),
            resolveSize(contentHeight + paddingTop + paddingBottom, heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        var y = paddingTop
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.visibility == GONE) continue
            val alignLeft = (index == 0) != rtl
            val x = if (alignLeft) paddingLeft else width - paddingRight - child.measuredWidth
            val childY = if (stacked) y else paddingTop +
                (height - paddingTop - paddingBottom - child.measuredHeight) / 2
            child.layout(x, childY, x + child.measuredWidth, childY + child.measuredHeight)
            if (stacked) y += child.measuredHeight + gap
        }
    }

    override fun generateDefaultLayoutParams() = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)

    override fun generateLayoutParams(attrs: AttributeSet) = LayoutParams(context, attrs)
}

class ModelActionGroup(context: Context, attrs: AttributeSet) : ViewGroup(context, attrs) {
    private val gap = (8 * resources.displayMetrics.density).roundToInt()
    private val endAligned: Boolean
    private val lines = ArrayList<List<View>>()

    init {
        val attributes = context.obtainStyledAttributes(attrs, intArrayOf(android.R.attr.gravity))
        endAligned = attributes.getInt(0, Gravity.START) and Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK == Gravity.END
        attributes.recycle()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val available = (MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight).coerceAtLeast(0)
        lines.clear()
        var line = ArrayList<View>()
        var lineWidth = 0
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.visibility == GONE) continue
            child.measure(MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            val spacing = if (line.isEmpty()) 0 else gap
            if (line.isNotEmpty() && lineWidth + spacing + child.measuredWidth > available) {
                lines.add(line)
                line = ArrayList()
                lineWidth = 0
            }
            lineWidth += child.measuredWidth + if (line.isEmpty()) 0 else gap
            line.add(child)
        }
        if (line.isNotEmpty()) lines.add(line)
        var contentWidth = 0
        var contentHeight = 0
        for (row in lines) {
            contentWidth = max(contentWidth, row.sumOf { it.measuredWidth } + gap * (row.size - 1))
            contentHeight += row.maxOf { it.measuredHeight }
        }
        contentHeight += gap * (lines.size - 1).coerceAtLeast(0)
        setMeasuredDimension(resolveSize(contentWidth + paddingLeft + paddingRight, widthMeasureSpec),
            resolveSize(contentHeight + paddingTop + paddingBottom, heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        var y = paddingTop
        for (row in lines) {
            val rowWidth = row.sumOf { it.measuredWidth } + gap * (row.size - 1)
            val rowHeight = row.maxOf { it.measuredHeight }
            var x = if (endAligned != rtl) width - paddingRight - rowWidth else paddingLeft
            for (child in if (rtl) row.asReversed() else row) {
                val childY = y + (rowHeight - child.measuredHeight) / 2
                child.layout(x, childY, x + child.measuredWidth, childY + child.measuredHeight)
                x += child.measuredWidth + gap
            }
            y += rowHeight + gap
        }
    }

    override fun generateDefaultLayoutParams() = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)

    override fun generateLayoutParams(attrs: AttributeSet) = LayoutParams(context, attrs)
}
