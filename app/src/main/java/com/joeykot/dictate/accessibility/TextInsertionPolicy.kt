package com.joeykot.dictate.accessibility

internal fun editableTextForInsertion(
    nodeText: CharSequence?,
    isShowingHintText: Boolean,
): String = if (isShowingHintText) "" else nodeText?.toString().orEmpty()
