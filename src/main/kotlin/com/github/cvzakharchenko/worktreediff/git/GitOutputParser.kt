package com.github.cvzakharchenko.worktreediff.git

import java.nio.charset.StandardCharsets

internal fun ByteArray.toNulSeparatedStrings(): List<String> {
    if (isEmpty()) {
        return emptyList()
    }

    val result = mutableListOf<String>()
    var start = 0
    for (index in indices) {
        if (this[index].toInt() == 0) {
            result += copyOfRange(start, index).toString(StandardCharsets.UTF_8)
            start = index + 1
        }
    }
    if (start < size) {
        result += copyOfRange(start, size).toString(StandardCharsets.UTF_8)
    }
    return result
}
