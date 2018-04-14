package net.aquadc.properties.internal

import net.aquadc.properties.MutableProperty
import net.aquadc.properties.Property

/**
 * Single-threaded mutable property.
 * Will remember thread it was created on and throw an exception if touched from wrong thread.
 */
class UnsMutableProperty<T>(
        value: T
) : PropNotifier<T>(Thread.currentThread()), MutableProperty<T> {

    private var _notifyAllFunc: ((T, T) -> Unit)? = null
    private val notifyAllFunc: (T, T) -> Unit
        get() = _notifyAllFunc ?: { old: T, new: T -> valueChanged(old, new, null) }.also { _notifyAllFunc = it }

    var valueRef: T = value

    override var value: T
        get() {
            checkThread()
            val sample = sample
            return if (sample == null) valueRef else sample.value
        }
        set(newValue) {
            checkThread()
            dropBinding()

            // set value then
            val old = valueRef
            valueRef = newValue

            valueChanged(old, newValue, null)
        }

    private var sample: Property<T>? = null

    override fun bindTo(sample: Property<T>) {
        checkThread()
        val newSample = if (sample.mayChange) sample else null
        val oldSample = this.sample
        this.sample = newSample
        oldSample?.removeChangeListener(notifyAllFunc)
        newSample?.addChangeListener(notifyAllFunc)

        val old = valueRef
        val new = sample.value
        valueRef = new
        valueChanged(old, new, null)
    }

    override fun casValue(expect: T, update: T): Boolean {
        checkThread()
        dropBinding()
        return if (valueRef === expect) {
            valueRef = update
            valueChanged(expect, update, null)
            true
        } else {
            false
        }
    }

    private fun dropBinding() {
        val oldSample = sample
        oldSample?.removeChangeListener(notifyAllFunc)
        sample = null
    }

}
