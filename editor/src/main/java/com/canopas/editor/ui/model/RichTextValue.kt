package com.canopas.editor.ui.model

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import com.canopas.editor.ui.data.ContentType
import com.canopas.editor.ui.data.ContentValue
import com.canopas.editor.ui.data.RichTextPart
import com.canopas.editor.ui.data.RichTextStyle
import kotlin.math.max
import kotlin.math.min

@Immutable
internal data class RichTextValue internal constructor(
    internal var textFieldValue: TextFieldValue,
    internal val currentStyles: MutableSet<RichTextStyle> = mutableSetOf(),
    internal val parts: MutableList<RichTextPart> = mutableListOf()
) : ContentValue() {

    constructor(
        text: String = ""
    ) : this(textFieldValue = TextFieldValue(text = text))

    val text get() = textFieldValue.text

    override val type: ContentType = ContentType.RICH_TEXT
    override var isFocused: Boolean = false

    internal val visualTransformation
        get() = VisualTransformation {
            TransformedText(
                text = annotatedString,
                offsetMapping = OffsetMapping.Identity
            )
        }

    private val annotatedString
        get() = buildAnnotatedString {
            append(textFieldValue.text)
            parts.forEach { part ->
                val spanStyle = part.styles.fold(SpanStyle()) { spanStyle, richTextStyle ->
                    richTextStyle.applyStyle(spanStyle)
                }

                addStyle(
                    style = spanStyle,
                    start = part.fromIndex,
                    end = part.toIndex + 1,
                )
            }
        }

    fun toggleStyle(style: RichTextStyle): RichTextValue {
        return if (currentStyles.contains(style)) {
            removeStyle(style)
        } else {
            addStyle(style)
        }
    }

    private fun addStyle(vararg style: RichTextStyle): RichTextValue {
        currentStyles.addAll(style)
        applyStylesToSelectedText(*style)
        return this
    }

    fun updateStyles(newStyles: Set<RichTextStyle>): RichTextValue {
        currentStyles.clear()
        currentStyles.addAll(newStyles)
        applyStylesToSelectedText(*newStyles.toTypedArray())
        return this
    }

    private fun applyStylesToSelectedText(vararg style: RichTextStyle) {
        updateSelectedTextParts { part ->
            val styles = part.styles.toMutableSet()
            styles.addAll(style.toSet())

            part.copy(styles = styles)
        }
    }

    private fun removeStyle(vararg style: RichTextStyle): RichTextValue {
        currentStyles.removeAll(style.toSet())
        removeStylesFromSelectedText(*style)
        return this
    }

    private fun removeStylesFromSelectedText(vararg style: RichTextStyle) {
        updateSelectedTextParts { part ->
            val styles = part.styles.toMutableSet()
            styles.removeAll(style.toSet())

            part.copy(styles = styles)
        }
    }

    fun updateTextFieldValue(newValue: TextFieldValue): RichTextValue {
        var newTextFieldValue = newValue
        if (newTextFieldValue.text.length > textFieldValue.text.length) {
            newTextFieldValue = handleAddingCharacters(newTextFieldValue)
        } else if (newTextFieldValue.text.length < textFieldValue.text.length) {
            handleRemovingCharacters(newTextFieldValue)
        }

        updateCurrentStyles(newTextFieldValue = newTextFieldValue)

        collapseParts(textLastIndex = newTextFieldValue.text.lastIndex)

        textFieldValue = newTextFieldValue
        return this

    }

    private fun handleAddingCharacters(
        newValue: TextFieldValue,
    ): TextFieldValue {

        var currentStyles = currentStyles.toSet()
        val typedChars = newValue.text.length - textFieldValue.text.length
        val startTypeIndex = newValue.selection.min - typedChars

        if (newValue.text.getOrNull(startTypeIndex) == '\n') {
            removeTitleStylesIfAny()
            currentStyles = setOf()
        }

        val startRichTextPartIndex = parts.indexOfFirst {
            (startTypeIndex - 1) in it.fromIndex..it.toIndex
        }
        val endRichTextPartIndex = parts.indexOfFirst {
            startTypeIndex in it.fromIndex..it.toIndex
        }

        val startRichTextPart = parts.getOrNull(startRichTextPartIndex)
        val endRichTextPart = parts.getOrNull(endRichTextPartIndex)

        if (currentStyles == startRichTextPart?.styles) {
            parts[startRichTextPartIndex] = startRichTextPart.copy(
                toIndex = startRichTextPart.toIndex + typedChars
            )

            if (startRichTextPartIndex < parts.lastIndex) {
                moveParts(
                    fromIndex = startRichTextPartIndex + 1,
                    toIndex = parts.lastIndex,
                    by = typedChars,
                )
            }
        } else if (currentStyles == endRichTextPart?.styles) {
            parts[endRichTextPartIndex] = endRichTextPart.copy(
                toIndex = endRichTextPart.toIndex + typedChars
            )

            if (endRichTextPartIndex < parts.lastIndex) {
                moveParts(
                    fromIndex = endRichTextPartIndex + 1,
                    toIndex = parts.lastIndex,
                    by = typedChars,
                )
            }
        } else if (startRichTextPart == endRichTextPart && startRichTextPart != null) {
            parts[startRichTextPartIndex] = startRichTextPart.copy(
                toIndex = startTypeIndex - 1
            )
            parts.add(
                startRichTextPartIndex + 1, startRichTextPart.copy(
                    fromIndex = startTypeIndex + typedChars,
                    toIndex = startRichTextPart.toIndex + typedChars
                )
            )
            parts.add(
                startRichTextPartIndex + 1, RichTextPart(
                    fromIndex = startTypeIndex,
                    toIndex = startTypeIndex + typedChars - 1,
                    styles = currentStyles
                )
            )

            if ((startRichTextPartIndex + 2) < parts.lastIndex) {
                moveParts(
                    fromIndex = startRichTextPartIndex + 3,
                    toIndex = parts.lastIndex,
                    by = typedChars,
                )
            }
        } else if (endRichTextPart == null) {
            parts.add(
                RichTextPart(
                    fromIndex = startTypeIndex,
                    toIndex = startTypeIndex + typedChars - 1,
                    styles = currentStyles
                )
            )
        } else {
            parts.add(
                startRichTextPartIndex + 1, RichTextPart(
                    fromIndex = startTypeIndex,
                    toIndex = startTypeIndex + typedChars - 1,
                    styles = currentStyles
                )
            )

            if ((startRichTextPartIndex + 1) < parts.lastIndex) {
                moveParts(
                    fromIndex = startRichTextPartIndex + 2,
                    toIndex = parts.lastIndex,
                    by = typedChars,
                )
            }
        }

        return newValue
    }

    private fun moveParts(
        fromIndex: Int,
        toIndex: Int,
        by: Int
    ) {
        val start = max(fromIndex, 0)
        val end = min(toIndex, parts.lastIndex)
        (start..end).forEach { index ->
            parts[index] = parts[index].copy(
                fromIndex = parts[index].fromIndex + by,
                toIndex = parts[index].toIndex + by,
            )
        }
    }

    private fun removeTitleStylesIfAny() {
        val hasTitleStyle = currentStyles.any { it.isTitleStyles() }
        if (hasTitleStyle) clearStyles()
    }

    private fun clearStyles() {
        currentStyles.clear()
        removeAllStylesFromSelectedText()
    }

    private fun removeAllStylesFromSelectedText() {
        updateSelectedTextParts { part ->
            part.copy(
                styles = emptySet()
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

        val removedIndexes = mutableSetOf<Int>()

        parts.forEachIndexed { index, part ->
            if (removeRange.last < part.fromIndex) {
                // Example: L|orem| ipsum *dolor* sit amet.
                parts[index] = part.copy(
                    fromIndex = part.fromIndex - removedChars,
                    toIndex = part.toIndex - removedChars
                )
            } else if (removeRange.first <= part.fromIndex && removeRange.last >= part.toIndex) {
                // Example: Lorem| ipsum *dolor* si|t amet.
                parts[index] = part.copy(
                    fromIndex = 0,
                    toIndex = 0
                )
                removedIndexes.add(index)
            } else if (removeRange.first <= part.fromIndex) {
                // Example: Lorem| ipsum *dol|or* sit amet.
                parts[index] = part.copy(
                    fromIndex = max(0, removeRange.first),
                    toIndex = min(newTextFieldValue.text.length, part.toIndex - removedChars)
                )
            } else if (removeRange.last <= part.toIndex) {
                // Example: Lorem ipsum *d|olo|r* sit amet.
                parts[index] = part.copy(
                    toIndex = part.toIndex - removedChars
                )
            } else if (removeRange.first < part.toIndex) {
                // Example: Lorem ipsum *dol|or* si|t amet.
                parts[index] = part.copy(
                    toIndex = removeRange.first
                )
            }
        }
        removedIndexes.reversed().forEach { parts.removeAt(it) }
    }

    private fun updateCurrentStyles(
        newTextFieldValue: TextFieldValue
    ) {
        val newStyles = parts
            .firstOrNull {
                if (newTextFieldValue.selection.min == 0 && it.fromIndex == 0) {
                    return@firstOrNull true
                }
                (newTextFieldValue.selection.min - 1) in (it.fromIndex..it.toIndex)
            }
            ?.styles
            ?: currentStyles

        setCurrentStyles(newStyles.toSet())
    }

    private fun setCurrentStyles(currentStyles: Set<RichTextStyle>) {
        this.currentStyles.clear()
        this.currentStyles.addAll(currentStyles)
    }

    private fun updateSelectedTextParts(
        update: (part: RichTextPart) -> RichTextPart
    ) {
        if (textFieldValue.selection.collapsed) {
            return
        }

        val fromIndex = textFieldValue.selection.min
        val toIndex = textFieldValue.selection.max

        val selectedParts = parts.filter { part ->
            part.fromIndex < toIndex && part.toIndex >= fromIndex
        }

        selectedParts.forEach { part ->
            val index = parts.indexOf(part)
            if (index !in parts.indices) return@forEach

            if (part.fromIndex < fromIndex && part.toIndex >= toIndex) {
                parts[index] = part.copy(
                    toIndex = fromIndex - 1
                )
                parts.add(
                    index + 1,
                    update(
                        part.copy(
                            fromIndex = fromIndex,
                            toIndex = toIndex - 1
                        )
                    )
                )
                parts.add(
                    index + 2,
                    part.copy(
                        fromIndex = toIndex,
                    )
                )
            } else if (part.fromIndex < fromIndex) {
                parts[index] = part.copy(
                    toIndex = fromIndex - 1
                )
                parts.add(
                    index + 1,
                    update(
                        part.copy(
                            fromIndex = fromIndex,
                        )
                    )
                )
            } else if (part.toIndex >= toIndex) {
                parts[index] = update(
                    part.copy(
                        toIndex = toIndex - 1
                    )
                )
                parts.add(
                    index + 1,
                    part.copy(
                        fromIndex = toIndex,
                    )
                )
            } else {
                parts[index] = update(part)
            }
        }
    }


    private fun collapseParts(
        textLastIndex: Int
    ) {
        val startRangeMap = mutableMapOf<Int, Int>()
        val endRangeMap = mutableMapOf<Int, Int>()
        val removedIndexes = mutableSetOf<Int>()

        parts.forEachIndexed { index, part ->
            startRangeMap[part.fromIndex] = index
            endRangeMap[part.toIndex] = index
        }

        parts.forEachIndexed { index, part ->
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
                if (parts[otherRangeIndex].styles == part.styles) {
                    parts[index] = part.copy(
                        toIndex = parts[otherRangeIndex].toIndex
                    )

                    // Remove collapsed values
                    startRangeMap.remove(end + 1)
                    endRangeMap.remove(end)
                    removedIndexes.add(otherRangeIndex)
                }
            }

            if (endRangeMap.containsKey(start - 1)) {
                val otherRangeIndex = requireNotNull(endRangeMap[start - 1])
                if (parts[otherRangeIndex].styles == part.styles) {
                    parts[index] = part.copy(
                        fromIndex = parts[otherRangeIndex].fromIndex
                    )

                    // Remove collapsed values
                    startRangeMap.remove(start - 1)
                    endRangeMap.remove(start - 1)
                    removedIndexes.add(otherRangeIndex)
                }
            }

            parts[index] = parts[index].copy(
                fromIndex = max(0, parts[index].fromIndex),
                toIndex = min(textLastIndex, parts[index].toIndex),
            )
        }

        removedIndexes.reversed().forEach { parts.removeAt(it) }
    }

    internal fun merge(nextItem: RichTextValue): RichTextValue {
        val text = this.text + "\n" + nextItem.text
        val existingParts = ArrayList(this.parts)
        this.parts.addAll(nextItem.parts)
        moveParts(existingParts.size, this.parts.size, this.text.length + 1)
        this.textFieldValue = TextFieldValue(text, selection = TextRange(text.length))
        return this
    }

    fun hasStyle(style: RichTextStyle) = currentStyles.contains(style)

}