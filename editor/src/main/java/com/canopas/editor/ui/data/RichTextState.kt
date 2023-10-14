package com.canopas.editor.ui.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import kotlin.math.max
import kotlin.math.min

class RichTextState internal constructor(
    val richText: String = "",
    val spans: MutableList<RichTextPart> = mutableListOf()
) {

    internal var textFieldValue by mutableStateOf(TextFieldValue(richText))
        private set

    private val currentStyles = mutableStateListOf<SpanStyle>()

    internal var visualTransformation: VisualTransformation by mutableStateOf(VisualTransformation.None)

    private var annotatedString by mutableStateOf(AnnotatedString(text = ""))

    val text get() = textFieldValue.text

    private val selection
        get() = textFieldValue.selection


    init {
        updateTextFieldValue()
    }

    internal fun updateTextFieldValue(newValue: TextFieldValue = textFieldValue) {
        if (
            newValue.text == textFieldValue.text &&
            newValue.selection != textFieldValue.selection
        ) {
            textFieldValue = newValue
        } else {
            updateAnnotatedString(newValue)
        }

        updateCurrentSpanStyle()
    }

    private fun updateAnnotatedString(newValue: TextFieldValue = textFieldValue) {
        annotatedString = buildAnnotatedString {
            append(newValue.text)
            spans.forEach { part ->
                part.spanStyle.let {
                    addStyle(
                        style = it,
                        start = part.fromIndex,
                        end = part.toIndex + 1,
                    )
                }
            }
        }
        textFieldValue = newValue
        visualTransformation = VisualTransformation { _ ->
            TransformedText(
                annotatedString,
                OffsetMapping.Identity
            )
        }
    }

    private fun updateCurrentSpanStyle() {
        this.currentStyles.clear()

        val currentStyles = if (selection.collapsed) {
            getRichSpanByTextIndex(textIndex = selection.min - 1)
        } else {
            getRichSpanListByTextRange(selection).distinct()
        }

        this.currentStyles.addAll(currentStyles)
    }

    private fun getRichSpanByTextIndex(textIndex: Int): List<SpanStyle> {
        return spans.filter { textIndex >= it.fromIndex && textIndex <= it.toIndex }
            .map { it.spanStyle }
    }

    private fun getRichSpanListByTextRange(selection: TextRange): List<SpanStyle> {
        val matchingSpans = mutableListOf<SpanStyle>()

        for (part in spans) {
            val partRange = TextRange(part.fromIndex, part.toIndex)
            if (selection.overlaps(partRange)) {
                part.spanStyle.let {
                    matchingSpans.add(it)
                }
            }
        }

        return matchingSpans
    }

    private fun TextRange.overlaps(range: TextRange): Boolean {
        return end > range.start && start < range.end
    }

    fun toggleStyle(style: SpanStyle) {
        if (currentStyles.contains(style)) {
            removeStyle(style)
        } else {
            addStyle(style)
        }
    }

    fun updateStyle(style: SpanStyle) {
        currentStyles.clear()
        currentStyles.add(style)

        if (!selection.collapsed) {
            applyStylesToSelectedText(style)
        }
    }

    private fun addStyle(style: SpanStyle) {
        if (!currentStyles.contains(style)) {
            currentStyles.add(style)
        }

        if (!selection.collapsed) {
            applyStylesToSelectedText(style)
        }
    }

    private fun applyStylesToSelectedText(style: SpanStyle) {
        updateSelectedTextParts { part ->
            part.copy(spanStyle = style)
        }
        updateTextFieldValue()
    }

    private fun removeStyle(style: SpanStyle) {
        if (currentStyles.contains(style)) {
            currentStyles.remove(style)
        }

        if (!selection.collapsed)
            removeStylesFromSelectedText(style)
    }

    private fun removeStylesFromSelectedText(style: SpanStyle) {
        updateSelectedTextParts { part ->
            part.copy(spanStyle = style)
        }
        updateTextFieldValue()
    }

    fun onTextFieldValueChange(newTextFieldValue: TextFieldValue) {
        if (newTextFieldValue.text.length > textFieldValue.text.length)
            handleAddingCharacters(newTextFieldValue)
        else if (newTextFieldValue.text.length < textFieldValue.text.length)
            handleRemovingCharacters(newTextFieldValue)
        else if (
            newTextFieldValue.text == textFieldValue.text &&
            newTextFieldValue.selection != textFieldValue.selection
        ) {
            adjustSelection(newTextFieldValue.selection)
            return
        }

        collapseParts(textLastIndex = newTextFieldValue.text.lastIndex)
        updateTextFieldValue(newTextFieldValue)
    }

    private fun adjustSelection(selection: TextRange? = null) {
        if (selection != null) {
            updateTextFieldValue(textFieldValue.copy(selection = selection))
        }
    }

    private fun handleAddingCharacters(newValue: TextFieldValue) {
        val typedChars = newValue.text.length - textFieldValue.text.length
        val startTypeIndex = newValue.selection.min - typedChars

        moveSpans(startTypeIndex, typedChars)

        val selectedStyles = currentStyles.toMutableList()
        val startParts = spans.filter { startTypeIndex - 1 in it.fromIndex..it.toIndex }
        val endParts = spans.filter { startTypeIndex in it.fromIndex..it.toIndex }
        val commonParts = startParts.intersect(endParts.toSet())

        startParts.filter { it !in commonParts }
            .forEach {
                if (selectedStyles.contains(it.spanStyle)) {
                    val index = spans.indexOf(it)
                    spans[index] = it.copy(toIndex = it.toIndex + typedChars)
                    selectedStyles.remove(it.spanStyle)
                }
            }

        endParts.filter { it !in commonParts }
            .forEach { processSpan(it, typedChars, startTypeIndex, selectedStyles, true) }

        commonParts.forEach { processSpan(it, typedChars, startTypeIndex, selectedStyles) }

        selectedStyles.forEach {
            spans.add(
                RichTextPart(
                    fromIndex = startTypeIndex,
                    toIndex = startTypeIndex + typedChars - 1,
                    spanStyle = it
                )
            )
        }
    }

    private fun processSpan(
        richTextPart: RichTextPart,
        typedChars: Int,
        startTypeIndex: Int,
        selectedStyles: MutableList<SpanStyle>,
        forward: Boolean = false
    ) {

        val newFromIndex = richTextPart.fromIndex + typedChars
        val newToIndex = richTextPart.toIndex + typedChars

        val index = spans.indexOf(richTextPart)
        if (selectedStyles.contains(richTextPart.spanStyle)) {
            spans[index] = richTextPart.copy(toIndex = newToIndex)
            selectedStyles.remove(richTextPart.spanStyle)
        } else {
            if (forward) {
                spans[index] = richTextPart.copy(
                    fromIndex = newFromIndex,
                    toIndex = newToIndex
                )
            } else {
                spans[index] = richTextPart.copy(toIndex = startTypeIndex - 1)
                spans.add(
                    index + 1, richTextPart.copy(
                        fromIndex = startTypeIndex + typedChars,
                        toIndex = newToIndex
                    )
                )
                selectedStyles.remove(richTextPart.spanStyle)
            }
        }
    }

    private fun moveSpans(startTypeIndex: Int, by: Int) {
        val filteredSpans = spans.filter { it.fromIndex > startTypeIndex }

        filteredSpans.forEach {
            val index = spans.indexOf(it)
            spans[index] = it.copy(
                fromIndex = it.fromIndex + by,
                toIndex = it.toIndex + by,
            )
        }
    }

    private fun forwardParts(
        fromIndex: Int,
        toIndex: Int,
        by: Int
    ) {
        val start = max(fromIndex, 0)
        val end = min(toIndex, spans.lastIndex)
        (start..end).forEach { index ->
            spans[index] = spans[index].copy(
                fromIndex = spans[index].fromIndex + by,
                toIndex = spans[index].toIndex + by,
            )
        }
    }

    private fun handleRemovingCharacters(
        newTextFieldValue: TextFieldValue
    ) {
        val removedChars = textFieldValue.text.length - newTextFieldValue.text.length
        val startRemoveIndex = newTextFieldValue.selection.min + removedChars
        val endRemoveIndex = newTextFieldValue.selection.min
        val removeRange = endRemoveIndex until startRemoveIndex

        val iterator = spans.iterator()

        val partsCopy = spans.toMutableList()

        while (iterator.hasNext()) {
            val part = iterator.next()
            val index = partsCopy.indexOf(part)

            if (removeRange.last < part.fromIndex) {
                partsCopy[index] = part.copy(
                    fromIndex = part.fromIndex - removedChars,
                    toIndex = part.toIndex - removedChars
                )
            } else if (removeRange.first <= part.fromIndex && removeRange.last >= part.toIndex) {
                // Remove the element from the copy.
                partsCopy.removeAt(index)
            } else if (removeRange.first <= part.fromIndex) {
                partsCopy[index] = part.copy(
                    fromIndex = max(0, removeRange.first),
                    toIndex = min(newTextFieldValue.text.length, part.toIndex - removedChars)
                )
            } else if (removeRange.last <= part.toIndex) {
                partsCopy[index] = part.copy(toIndex = part.toIndex - removedChars)
            } else if (removeRange.first < part.toIndex) {
                partsCopy[index] = part.copy(toIndex = removeRange.first)
            }
        }

        spans.clear()
        spans.addAll(partsCopy)
    }

    private fun updateSelectedTextParts(
        update: (part: RichTextPart) -> RichTextPart
    ) {
        if (textFieldValue.selection.collapsed) {
            return
        }

        val fromIndex = textFieldValue.selection.min
        val toIndex = textFieldValue.selection.max

        val selectedParts = spans.filter { part ->
            part.fromIndex < toIndex && part.toIndex >= fromIndex
        }

//        if(selectedParts.isEmpty()){
//            currentStyles.first {
//
//            }
//        }
        selectedParts.forEach { part ->
            val index = spans.indexOf(part)
            if (index !in spans.indices) return@forEach

            if (part.fromIndex < fromIndex && part.toIndex >= toIndex) {
                spans[index] = part.copy(toIndex = fromIndex - 1)
                spans.add(
                    index + 1,
                    update(
                        part.copy(
                            fromIndex = fromIndex,
                            toIndex = toIndex - 1
                        )
                    )
                )
                spans.add(index + 2, part.copy(fromIndex = toIndex))
            } else if (part.fromIndex < fromIndex) {
                spans[index] = part.copy(
                    toIndex = fromIndex - 1
                )
                spans.add(index + 1, update(part.copy(fromIndex = fromIndex)))
            } else if (part.toIndex >= toIndex) {
                spans[index] = update(part.copy(toIndex = toIndex - 1))
                spans.add(index + 1, part.copy(fromIndex = toIndex))
            } else {
                spans[index] = update(part)
            }
        }
    }


    private fun collapseParts(
        textLastIndex: Int
    ) {
        val startRangeMap = mutableMapOf<Int, Int>()
        val endRangeMap = mutableMapOf<Int, Int>()
        val removedIndexes = mutableSetOf<Int>()

        val partsCopy = spans.toMutableList() // Create a copy of the original parts

        partsCopy.forEachIndexed { index, part ->
            startRangeMap[part.fromIndex] = index
            endRangeMap[part.toIndex] = index
        }

        partsCopy.forEachIndexed { index, part ->
            if (removedIndexes.contains(index)) {
                return@forEachIndexed
            }

            val start = part.fromIndex
            val end = part.toIndex

            if (end < start) {
                removedIndexes.add(index)
                return@forEachIndexed
            }

            if (startRangeMap.containsKey(end + 1)) {
                val otherRangeIndex = requireNotNull(startRangeMap[end + 1])
                if (partsCopy[otherRangeIndex].spanStyle == part.spanStyle) {
                    partsCopy[index] = part.copy(
                        toIndex = partsCopy[otherRangeIndex].toIndex
                    )

                    // Remove collapsed values
                    startRangeMap.remove(end + 1)
                    endRangeMap.remove(end)
                    removedIndexes.add(otherRangeIndex)
                }
            }

            if (endRangeMap.containsKey(start - 1)) {
                val otherRangeIndex = requireNotNull(endRangeMap[start - 1])
                if (partsCopy[otherRangeIndex].spanStyle == part.spanStyle) {
                    partsCopy[index] = part.copy(
                        fromIndex = partsCopy[otherRangeIndex].fromIndex
                    )

                    // Remove collapsed values
                    startRangeMap.remove(start - 1)
                    endRangeMap.remove(start - 1)
                    removedIndexes.add(otherRangeIndex)
                }
            }

            partsCopy[index] = partsCopy[index].copy(
                fromIndex = max(0, partsCopy[index].fromIndex),
                toIndex = min(textLastIndex, partsCopy[index].toIndex),
            )
        }

        removedIndexes.reversed().forEach { partsCopy.removeAt(it) }

        // Replace the original parts with the modified copy
        spans.clear()
        spans.addAll(partsCopy)
    }

    internal fun merge(nextItem: RichTextState) {
        val text = this.text + "\n" + nextItem.text
        val existingParts = ArrayList(this.spans)
        this.spans.addAll(nextItem.spans)
        forwardParts(existingParts.size, this.spans.size, this.text.length + 1)
        val newTextField = TextFieldValue(text, selection = TextRange(text.length))
        updateTextFieldValue(newTextField)
    }

    fun hasStyle(style: SpanStyle) = currentStyles.contains(style)

    companion object {
        internal val DefaultSpanStyle = SpanStyle()
    }
}

