package net.aquadc.persistence.sql

import androidx.annotation.RestrictTo
import net.aquadc.persistence.struct.BaseStruct
import net.aquadc.persistence.struct.FieldDef
import net.aquadc.persistence.struct.Schema
import net.aquadc.persistence.struct.forEachIndexed
import net.aquadc.persistence.struct.mapIndexed
import net.aquadc.properties.Property
import net.aquadc.properties.bind
import net.aquadc.properties.internal.ManagedProperty
import net.aquadc.properties.internal.Unset
import net.aquadc.properties.persistence.PropertyStruct


/**
 * Represents an active record — a container with some values and properties backed by an RDBMS row.
 */
open class Record<SCH : Schema<SCH>, ID : IdBound>
/**
 * Creates new record.
 * Note that such a record is managed and alive (will receive updates) only if created by [Dao].
 */
@Deprecated("Will become internal soon, making the whole class effectively final")
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
constructor(
        internal val table: Table<SCH, ID, *>,
        private val session: Session,
        val primaryKey: ID
) : BaseStruct<SCH>(table.schema), PropertyStruct<SCH> {

    internal val _session get() = session

    @Suppress("UNCHECKED_CAST", "UPPER_BOUND_VIOLATED")
    internal val dao: Dao<SCH, ID, *>
        get() = session.get(table as Table<SCH, ID, Record<SCH, ID>>)

    internal fun copyValues(): Array<Any?> {
        val size = values.size
        val out = arrayOfNulls<Any>(size)
        val flds = schema.fields
        repeat(size) { i ->
            out[i] = this[flds[i]]
        }
        return out
    }

    override fun <T> get(field: FieldDef<SCH, T, *>): T = when (field) {
        is FieldDef.Mutable -> prop(field).value
        is FieldDef.Immutable -> {
            val index = field.ordinal.toInt()
            val value = values[index]

            if (value === Unset) {
                @Suppress("UNCHECKED_CAST", "UPPER_BOUND_VIOLATED")
                val freshValue = dao.getClean(field, primaryKey)
                values[index] = freshValue
                freshValue
            } else value as T
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T> prop(field: FieldDef.Mutable<SCH, T, *>): SqlProperty<T> =
            values[field.ordinal.toInt()] as SqlProperty<T>

    @Deprecated("now we have normal relations")
    @Suppress("UNCHECKED_CAST") // id is not nullable, so Record<ForeSCH> won't be, too
    infix fun <ForeSCH : Schema<ForeSCH>, ForeID : IdBound, ForeREC : Record<ForeSCH, ForeID>>
            FieldDef.Mutable<SCH, ForeID, *>.toOne(foreignTable: Table<ForeSCH, ForeID, ForeREC>): SqlProperty<ForeREC> =
            (this as FieldDef.Mutable<SCH, ForeID?, *>).toOneNullable(foreignTable) as SqlProperty<ForeREC>

    @Deprecated("now we have normal relations")
    infix fun <ForeSCH : Schema<ForeSCH>, ForeID : IdBound, ForeREC : Record<ForeSCH, ForeID>>
            FieldDef.Mutable<SCH, ForeID?, *>.toOneNullable(foreignTable: Table<ForeSCH, ForeID, ForeREC>): SqlProperty<ForeREC?> =
            (this@Record prop this@toOneNullable).bind(
                    { id: ForeID? -> if (id == null) null else session[foreignTable].require(id) },
                    { it: ForeREC? -> it?.primaryKey }
            )

    @Deprecated("now we have normal relations")
    infix fun <ForeSCH : Schema<ForeSCH>, ForeID : IdBound, ForeREC : Record<ForeSCH, ForeID>>
            FieldDef.Mutable<ForeSCH, ID, *>.toMany(foreignTable: Table<ForeSCH, ForeID, ForeREC>): Property<List<ForeREC>> =
            session[foreignTable].select(this eq primaryKey)

    @JvmField @JvmSynthetic @Suppress("UNCHECKED_CAST")
    internal val values: Array<Any?/* = ManagedProperty<Transaction, T> | T */> =
            session[table as Table<SCH, ID, Record<SCH, ID>>].let { dao ->
                schema.mapIndexed(fields) { i, field ->
                    when (field) {
                        is FieldDef.Mutable -> ManagedProperty(dao, field as FieldDef<SCH, Any?, *>, primaryKey, Unset)
                        is FieldDef.Immutable -> Unset
                    }
                }
            }

    var isManaged: Boolean = true
        @JvmSynthetic internal set // cleared **before** real property unmanagement occurs

    @JvmSynthetic internal fun dropManagement() {
        val vals = values
        schema.forEachIndexed(fields) { i, field ->
            when (field) {
                is FieldDef.Mutable -> (vals[i] as ManagedProperty<*, *, *, *>).dropManagement()
                is FieldDef.Immutable -> { /* no-op */ }
            }.also { }
        }
    }

    override fun toString(): String =
            if (isManaged) super.toString()
            else buildString {
                append(this@Record.javaClass.simpleName).append(':')
                        .append(schema.javaClass.simpleName).append("(isManaged=false)")
            }

}
