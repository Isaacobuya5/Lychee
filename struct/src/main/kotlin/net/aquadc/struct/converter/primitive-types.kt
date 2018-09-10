package net.aquadc.struct.converter

import net.aquadc.struct.t
import java.lang.AssertionError
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types


internal abstract class SimpleConverter<T>(
        override val dataType: DataType,
        override val isNullable: Boolean
) : UniversalConverter<T>

private class BasicConverter<T>(
        private val javaType: Class<T>,
        dataType: DataType,
        isNullable: Boolean
) : SimpleConverter<T>(dataType, isNullable) {

    override fun bind(statement: PreparedStatement, index: Int, value: T) {
        val i = 1 + index
        return when (value) {
            is Boolean, is Short, is Int, is Long, is Float, is Double,
            is String,
            is ByteArray -> statement.setObject(i, value)
            is Byte -> statement.setInt(i, value.toInt())
            null -> statement.setNull(i, Types.NULL)
            else -> throw AssertionError()
        }
    }

    override fun get(resultSet: ResultSet, index: Int): T {
        val i = 1 + index
        @Suppress("IMPLICIT_CAST_TO_ANY", "UNCHECKED_CAST")
        return when (javaType) {
            t<Boolean>() -> resultSet.getBoolean(i)
            t<Byte>() -> resultSet.getByte(i)
            t<Short>() -> resultSet.getShort(i)
            t<Int>() -> resultSet.getInt(i)
            t<Long>() -> resultSet.getLong(i)
            t<Float>() -> resultSet.getFloat(i)
            t<Double>() -> resultSet.getDouble(i)
            t<String>() -> resultSet.getString(i)
            t<ByteArray>() -> resultSet.getBytes(i)
            else -> throw AssertionError()
        } as T
    }

    override fun toString(value: T): String =
            value.toString()

    override fun get(cursor: Nothing, index: Int): T =
            cursor

}

val bool: UniversalConverter<Boolean> = BasicConverter(t(), DataTypes.Bool, false)
val nullableBool: UniversalConverter<Boolean?> = BasicConverter(t(), DataTypes.Bool, true)

val byte: UniversalConverter<Byte> = BasicConverter(t(), DataTypes.Int8, false)
val nullableByte: UniversalConverter<Byte?> = BasicConverter(t(), DataTypes.Int8, true)

val short: UniversalConverter<Short> = BasicConverter(t(), DataTypes.Int16, false)
val nullableShort: UniversalConverter<Short?> = BasicConverter(t(), DataTypes.Int16, true)

val int: UniversalConverter<Int> = BasicConverter(t(), DataTypes.Int32, false)
val nullableInt: UniversalConverter<Int?> = BasicConverter(t(), DataTypes.Int32, true)

val long: UniversalConverter<Long> = BasicConverter(t(), DataTypes.Int64, false)
val nullableLong: UniversalConverter<Long?> = BasicConverter(t(), DataTypes.Int64, true)

val float: UniversalConverter<Float> = BasicConverter(t(), DataTypes.Float32, false)
val nullableFloat: UniversalConverter<Float?> = BasicConverter(t(), DataTypes.Float32, true)

val double: UniversalConverter<Double> = BasicConverter(t(), DataTypes.Float64, false)
val nullableDouble: UniversalConverter<Double?> = BasicConverter(t(), DataTypes.Float64, true)

val string: UniversalConverter<String> = BasicConverter(t(), DataTypes.LargeString, false)
val nullableString: UniversalConverter<String?> = BasicConverter(t(), DataTypes.LargeString, true)

@Deprecated("Note: if you mutate array, we won't notice — you must set() it in a transaction. " +
        "Consider using immutable ByteString instead.", ReplaceWith("byteString"))
val bytes: UniversalConverter<ByteArray> = BasicConverter(t(), DataTypes.LargeBlob, false)

@Deprecated("Note: if you mutate array, we won't notice — you must set() it in a transaction. " +
        "Consider using immutable ByteString instead.", ReplaceWith("nullableByteString"))
val nullableBytes: UniversalConverter<ByteArray?> = BasicConverter(t(), DataTypes.LargeBlob, true)


// Date is not supported because it's mutable and the most parts of it are deprecated 20+ years ago.
// TODO: To be considered...
