package net.aquadc.persistence.sql

import net.aquadc.persistence.extended.Tuple
import net.aquadc.persistence.extended.build
import net.aquadc.persistence.sql.blocking.Blocking
import net.aquadc.persistence.sql.blocking.Eager.cell
import net.aquadc.persistence.sql.blocking.Eager.col
import net.aquadc.persistence.sql.blocking.Eager.struct
import net.aquadc.persistence.sql.blocking.Eager.structList
import net.aquadc.persistence.struct.Struct
import net.aquadc.persistence.type.DataType
import net.aquadc.persistence.type.i32
import net.aquadc.persistence.type.string
import org.junit.Assert.assertEquals
import org.junit.Test


open class TemplatesTest {

    open val session: Session<out Blocking<out AutoCloseable>> get() = jdbcSession

    @Test fun <CUR : AutoCloseable> cell() {
        val session = session as Session<Blocking<CUR>>
        val kek = session.query("SELECT ? || 'kek'", string, cell<CUR, String>(string))
        assertEquals("lolkek", kek("lol"))
    }

    @Test fun <CUR : AutoCloseable> col() {
        val session = session as Session<Blocking<CUR>>
        val one = session.query("SELECT 1", col<CUR, Int>(i32))
        assertEquals(listOf(1), one())
    }

    @Test fun <CUR : AutoCloseable> row() {
        val session = session as Session<Blocking<CUR>>
        val sumAndMul = session.query(
                "SELECT ? + ?, ? * ?", i32, i32, i32, i32,
                struct<CUR, Tuple<Int, DataType.Simple<Int>, Int, DataType.Simple<Int>>>(projection(Tuple("f", i32, "s", i32)), BindBy.Position)
        )
        assertEquals(
                Pair(84, 48),
                sumAndMul(80, 4, 6, 8).let { Pair(it[it.schema.First], it[it.schema.Second]) }
        )
    }

    @Test fun <CUR : AutoCloseable> join() {
        val session = session as Session<Blocking<CUR>>
        val johnPk = session.withTransaction {
            val john = insert(UserTable, User.build("John", "john@doe.com"))
            insert(ContactTable, Contact.build("@johnDoe", john.primaryKey))
            john.primaryKey
        }

        val userAndContact = Tuple("u", User, "c", Contact)
        //  ^^^^^^^^^^^^^^ should inline this variable after inference fix
        val joined = projection(userAndContact) { arrayOf(
                Relation.Embedded(NestingCase, First)
              , Relation.Embedded(NestingCase, Second)
        ) }

        val userContact = session.query(
                "SELECT u._id as 'u.id', u.name as 'u.name', u.email as 'u.email'," +
                        "c._id as 'c.id', c.value as 'c.value', c.user_id as 'c.user_id' " +
                        "FROM users u INNER JOIN contacts c ON u._id = c.user_id WHERE u.name = ? LIMIT 1", string,
                struct<CUR, Tuple<Struct<Tuple<String, DataType.Simple<String>, String, DataType.Simple<String>>>, Tuple<String, DataType.Simple<String>, String, DataType.Simple<String>>, Struct<Tuple<String, DataType.Simple<String>, Long, DataType.Simple<Long>>>, Tuple<String, DataType.Simple<String>, Long, DataType.Simple<Long>>>>(joined, BindBy.Name)
        )
        val contact = userContact("John")
        val expectedJohn = joined.schema.build(
                User.build("John", "john@doe.com"),
                Contact.build("@johnDoe", johnPk)
        )
        assertEquals(expectedJohn, contact)

        val userContacts = session.query(
                "SELECT u._id as 'u.id', u.name as 'u.name', u.email as 'u.email'," +
                        "c._id as 'c.id', c.value as 'c.value', c.user_id as 'c.user_id' " +
                        "FROM users u INNER JOIN contacts c ON u._id = c.user_id WHERE u.name = ? AND u.email LIKE (? || '%') LIMIT 1", string, string,
                structList<CUR, Tuple<Struct<Tuple<String, DataType.Simple<String>, String, DataType.Simple<String>>>, Tuple<String, DataType.Simple<String>, String, DataType.Simple<String>>, Struct<Tuple<String, DataType.Simple<String>, Long, DataType.Simple<Long>>>, Tuple<String, DataType.Simple<String>, Long, DataType.Simple<Long>>>>(joined, BindBy.Name)
        )
        val contacts = userContacts("John", "john")
        assertEquals(listOf(expectedJohn), contacts)
    }

}
