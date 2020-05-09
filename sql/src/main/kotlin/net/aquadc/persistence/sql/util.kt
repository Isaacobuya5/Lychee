@file:Suppress("UNCHECKED_CAST") // this file is for unchecked casts :)
package net.aquadc.persistence.sql

import net.aquadc.persistence.newMap
import net.aquadc.persistence.sql.blocking.Blocking
import net.aquadc.persistence.struct.FieldDef
import net.aquadc.persistence.struct.FieldSet
import net.aquadc.persistence.struct.Schema
import net.aquadc.persistence.struct.StoredLens
import net.aquadc.persistence.struct.StoredNamedLens
import net.aquadc.persistence.struct.Struct
import net.aquadc.persistence.struct.StructSnapshot
import net.aquadc.persistence.struct.allFieldSet
import net.aquadc.persistence.struct.contains
import net.aquadc.persistence.struct.indexOf
import net.aquadc.persistence.struct.size
import net.aquadc.persistence.type.DataType
import net.aquadc.persistence.type.serialized
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentMap
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract


internal typealias UpdatesMap = MutableMap<
        Table<*, *>,
        MutableMap<
                IdBound,
                @ParameterName("valuesByOrdinal") Array<Any?>
                >
        >

@Suppress("NOTHING_TO_INLINE")
internal inline fun UpdatesMap() = newMap<
        Table<*, *>,
        MutableMap<
                IdBound,
                Array<Any?>
                >
        >()

internal inline fun <T, R> DataType<T>.flattened(func: (isNullable: Boolean, simple: DataType.Simple<T>) -> R): R =
        when (this) {
            is DataType.Nullable<*, *> -> {
                when (val actualType = actualType as DataType<T>) {
                    is DataType.Nullable<*, *> -> throw AssertionError()
                    is DataType.Simple -> func(true, actualType)
                    is DataType.Collect<*, *, *>,
                    is DataType.Partial<*, *> -> func(true, serialized(actualType))
                }
            }
            is DataType.Simple -> func(false, this)
            is DataType.Collect<*, *, *>,
            is DataType.Partial<*, *> -> func(false, serialized(this))
        }

internal inline fun <SCH : Schema<SCH>> bindQueryParams(
        condition: WhereCondition<SCH>, table: Table<SCH, *>, bind: (DataType<Any?>, idx: Int, value: Any?) -> Unit
) {
    val size = condition.size
    if (size > 0) {
        val argCols = arrayOfNulls<StoredLens<SCH, *, *>>(size)
        val argValues = arrayOfNulls<Any>(size)
        condition.setValuesTo(0, argCols, argValues)
        val cols = table.columns
        val indices = table.columnIndices
        for (i in 0 until size) {
            val colIndex = indices[argCols[i]]!!
            val column = cols[colIndex]
            check(argCols[i]!!.type(table.schema) == column.type(table.schema))
            // I hope `argCols[i].type` is the same as `column.type`, but let's overcare :)

            bind(column.type(table.schema) as DataType<Any?>, i, argValues[i])
            // erase its type and assume that caller is clever enough
        }
    }
}

internal inline fun <SCH : Schema<SCH>> bindInsertionParams(
        table: Table<SCH, *>,
        data: Struct<SCH>,
        bind: (DataType<Any?>, idx: Int, value: Any?) -> Unit
) {
    val columns = table.managedColumns
    arrayOfNulls<Any>(columns.size).also { flatten(table.recipe, it, data, 0, 0) }.forEachIndexed { idx, value ->
        bind(columns[idx].type(table.schema) as DataType<Any?>, idx, value)
    }
}

internal inline fun bindValues(
        columnTypes: Any, values: Any?, bind: (DataType<Any?>, idx: Int, value: Any?) -> Unit
): Int = if (columnTypes is Array<*>) {
    columnTypes as Array<DataType<*>>
    values as Array<*>?
    columnTypes.forEachIndexed { i, type ->
        bind(type as DataType<Any?>, i, values?.get(i))
    }
    columnTypes.size
} else {
    bind(columnTypes as DataType<Any?>, 0, values)
    1
}

internal inline fun <K, V : Any> ConcurrentMap<K, WeakReference<V>>.getOrPutWeak(key: K, create: () -> V): V =
        getOrPutWeak(key, create) { _, v -> v }

@UseExperimental(ExperimentalContracts::class)
internal inline fun <K, V : Any, R> ConcurrentMap<K, WeakReference<V>>.getOrPutWeak(key: K, create: () -> V, success: (WeakReference<V>, V) -> R): R {
    contract {
        callsInPlace(success, InvocationKind.EXACTLY_ONCE)
    }

    while (true) {
        val ref = getOrPut(key) {
            // putIfAbsent here may return either newly created or concurrently inserted value
            WeakReference(create())
        }
        val value = ref.get()
        if (value === null) remove(key, ref)
        else return success(ref, value)
    }
}

internal inline fun <T, reified R> Array<T>.mapIndexedToArray(transform: (Int, T) -> R): Array<R> {
    val array = arrayOfNulls<R>(size)
    for (i in indices) {
        array[i] = transform(i, this[i])
    }
    @Suppress("UNCHECKED_CAST") // now it's filled with items and not thus not nullable
    return array as Array<R>
}

/**
 * Transforms flat column values to in-memory instance.
 * Puts the resulting [StructSnapshot] into [mutColumnValues] at [_dstPos].
 */
@Suppress("UPPER_BOUND_VIOLATED")
internal fun inflate(
        recipe: Array<out Table.Nesting>,
        mutColumnValues: Array<Any?>,
        _srcPos: Int,
        _dstPos: Int,
        _recipeOffset: Int
) {
    val start = recipe[_recipeOffset] as Table.Nesting.StructStart

    var srcPos = _srcPos
    val schema = start.unwrappedType.schema
    val fieldSet = if (start.hasFieldSet) {
        (mutColumnValues[srcPos++] as Long?)?.let { FieldSet<Schema<*>, FieldDef<Schema<*>, *, *>>(it) }
    } else { // no fieldSet implies it's a non-partial Struct
        schema.allFieldSet<Schema<*>>()
    }

    var dstPos = _dstPos
    var lastMovedFieldIdx = -1
    var depth = 0
    var recipeOffset = _recipeOffset
    val fields = schema.fields
    loop@ while (++recipeOffset < recipe.size) { // evaluate nesting commands, start-end pairs with some nesting
        when (val nesting = recipe[recipeOffset]) {
            is Table.Nesting.StructStart -> {

                // gonna recurse and inflate nested stuff, but first let's move preceding field values up
                val myField = nesting.myField!!
                while (++lastMovedFieldIdx < myField.ordinal.toInt()) {
                    val value = mutColumnValues[srcPos++]
                    if (fields[lastMovedFieldIdx] in fieldSet)
                        mutColumnValues[dstPos++] = value
                }

                // now lastMovedFieldIdx == nesting.myField.ordinal, let's recurse
                if (myField in fieldSet)
                    inflate(recipe, mutColumnValues, srcPos, dstPos++, recipeOffset)
                srcPos += nesting.colCount

                // and skip all nesting commands consumed by the recursive call or ignored due to empty values
                // argh, I really miss references to local variables now
                depth++
                while (depth > 0) {
                    if (recipe[++recipeOffset] is Table.Nesting.StructStart) depth++
                    else depth--
                }
                // depth = 0 at this point, meaning that we're skipped nested structs
            }
            is Table.Nesting.StructEnd -> {
                if (depth-- == 0) break@loop // if depth was 0, we've met enclosing (not ours) struct end
            }
        }
    }

    // move all trailing values up — some copy-paste here
    while (++lastMovedFieldIdx < fields.size) {
        val value = mutColumnValues[srcPos++]
        if (fields[lastMovedFieldIdx] in fieldSet)
            mutColumnValues[dstPos++] = value
    }

    // yay! commit & push
    val t = start.unwrappedType as DataType.Partial<Any?, Any?>
    fieldSet as FieldSet<Any?, FieldDef<Any?, *, *>>?
    mutColumnValues[_dstPos] =
            if (fieldSet == null) null
            else t.load(fieldSet, when (fieldSet.size.toInt()) {
                0 -> null
                1 -> mutColumnValues[_dstPos]
                else -> mutColumnValues.copyOfRange(_dstPos, _dstPos + fieldSet.size)
            })
}

/**
 * Scatters in-memory value to column values.
 */
@Suppress("UPPER_BOUND_VIOLATED")
internal fun flatten(
        recipe: Array<out Table.Nesting>,
        out: Array<Any?>,
        value: Any?,
        _dstPos: Int,
        _recipeOffset: Int
) {
    val start = recipe[_recipeOffset] as Table.Nesting.StructStart
    var dstPos = _dstPos
    val type = start.type ?: start.unwrappedType // OMG, such a hack:
    // unwrappedType is a correct type for non-embedded, top-level struct

    if (start.hasFieldSet && type is DataType.Nullable<*, *> && value === null)
        return // fieldSet is null, all fields are nulls, nothing to do here -------------------------------------------

    val erased = start.unwrappedType as DataType.Partial<Any?, *>

    val fieldSet =
            if (start.hasFieldSet) (erased.fields(value) as FieldSet<Schema<*>, FieldDef<Schema<*>, *, *>>).also {
                out[dstPos++] = it.bitSet
            } else erased.schema.allFieldSet<Schema<*>>()

    val fields = start.unwrappedType.schema.fields
    when (fieldSet.size.toInt()) {
        0 -> { /* nothing to do here */ }
        1 -> {
            val fieldValue = erased.store(value)
            flattenFieldValues(_recipeOffset, { fieldValue }, recipe, fields, fieldSet, out, dstPos)
        }
        else -> {
            val fieldValues = erased.store(value) as Array<Any?> // fixme allocation
            flattenFieldValues(_recipeOffset, { f ->
                fieldValues[fieldSet.indexOf<Schema<*>>(f as FieldDef<Schema<*>, *, *>).toInt()]
            }, recipe, fields, fieldSet, out, dstPos)
        }
    }
}

@Suppress("UPPER_BOUND_VIOLATED")
private inline fun flattenFieldValues(
        _recipeOffset: Int, fieldValue: (FieldDef<out Schema<*>, *, *>) -> Any?, recipe: Array<out Table.Nesting>,
        fields: Array<out FieldDef<out Schema<*>, out Any?, *>>, fieldSet: FieldSet<Schema<*>, FieldDef<Schema<*>, *, *>>,
        out: Array<Any?>, _dstPos: Int
) {
    var dstPos = _dstPos
    var lastSetFieldIdx = -1
    var depth = 0
    var recipeOffset = _recipeOffset
    loop@ while (++recipeOffset < recipe.size) { // evaluate nesting commands, start-end pairs with some nesting
        when (val nesting = recipe[recipeOffset]) {
            is Table.Nesting.StructStart -> {
                // gonna recurse and flatten nested stuff, but first let's set all preceding field values up
                val myField = nesting.myField!!
                while (++lastSetFieldIdx < myField.ordinal.toInt()) {
                    val field = fields[lastSetFieldIdx]
                    if (field in fieldSet) out[dstPos] = fieldValue(field)
                    dstPos++
                }

                // now lastSetFieldIdx == nesting.myField.ordinal, let's recurse
                if (myField in fieldSet)
                    flatten(recipe, out, fieldValue(myField), dstPos, recipeOffset)
                dstPos += nesting.colCount

                // and skip all nesting commands consumed by the recursive call or ignored due to empty values
                // argh, I really miss references to local variables now
                depth++
                while (depth > 0) {
                    if (recipe[++recipeOffset] is Table.Nesting.StructStart) {
                        depth++
                    } else {
                        depth--
                    }
                }
                // depth = 0 at this point, meaning that we're skipped nested structs
            }
            is Table.Nesting.StructEnd -> {
                if (depth-- == 0) break@loop // if depth was 0, we've met enclosing (not ours) struct end
            }
        }
    }

    // assign trailing values
    while (++lastSetFieldIdx < fields.size) {
        val field = fields[lastSetFieldIdx]
        if (field in fieldSet) out[dstPos] = fieldValue(field)
        dstPos++
    }
}

@Suppress(
        "UPPER_BOUND_VIOLATED",
        "NOTHING_TO_INLINE" // inline: please issue a compiler error if inner contains() call is recursive
)
private inline operator fun FieldSet<Schema<*>, *>?.contains(field: FieldDef<*, *, *>): Boolean =
        this != null && this.contains<Schema<*>>(field as FieldDef<Schema<*>, *, *>)

internal fun <CUR> Blocking<CUR>.row(
        cursor: CUR, offset: Int, columnNames: Array<out CharSequence>, columnTypes: Array<out DataType<*>>, bindBy: BindBy
): Array<Any?> = when (bindBy) {
    BindBy.Name -> rowByName(cursor, columnNames, columnTypes)
    BindBy.Position -> rowByPosition(cursor, offset, columnTypes)
}

internal fun <SCH : Schema<SCH>, CUR, R> Blocking<CUR>.cell(
        cursor: CUR, table: Table<SCH, *>, column: StoredNamedLens<SCH, R, out DataType<R>>, bindBy: BindBy
): R = when (bindBy) {
    BindBy.Name -> cellByName(cursor, column.name(table.schema), column.type(table.schema))
    BindBy.Position -> cellAt(cursor, table.managedColumns.forceIndexOf(column), column.type(table.schema))
}

private fun Array<out Any>.forceIndexOf(element: Any): Int {
    //      note: ^^^^^^^ unlike indexOf, there's no special case for null, just 'cause we don't need it
    for (index in indices)
        if (element == this[index])
            return index
    throw NoSuchElementException(element.toString() + " !in " + contentToString())
}

internal fun <CUR, SCH : Schema<SCH>> Blocking<CUR>.mapRow(
        bindBy: BindBy,
        cur: CUR,
        colNames: Array<out CharSequence>,
        colTypes: Array<out DataType<*>>,
        recipe: Array<out Table.Nesting>
): StructSnapshot<SCH> {
    val firstValues = row(cur, 0, colNames, colTypes, bindBy)
    inflate(recipe, firstValues, 0, 0, 0)
    @Suppress("UNCHECKED_CAST")
    return firstValues[0] as StructSnapshot<SCH>
}

@PublishedApi @JvmField internal val throwNse = { throw NoSuchElementException() }
