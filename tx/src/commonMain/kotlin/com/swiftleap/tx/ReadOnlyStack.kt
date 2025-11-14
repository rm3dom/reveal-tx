@file:MustUseReturnValue

package com.swiftleap.tx

//Slow readonly stack
internal class ReadOnlyStack<T>(private val elements: List<T> = emptyList()) {

    val size: Int get() = elements.size

    val isEmpty: Boolean get() = elements.isEmpty()

    fun push(item: T): ReadOnlyStack<T> = ReadOnlyStack(elements + item)

    fun pop(): Pair<T, ReadOnlyStack<T>>? {
        if (elements.isEmpty()) return null
        val topElement = elements.lastOrNull() ?: return null
        val newStack = ReadOnlyStack(elements.dropLast(1)) // Creates a new list without the last item
        return Pair(topElement, newStack)
    }

    fun peek(): T? = if (elements.isEmpty()) null else elements.lastOrNull()
}
