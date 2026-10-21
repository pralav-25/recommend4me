package io.github.vlsergey.recommend4me.database

import org.jooq.ConnectionProvider
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.sql.Blob
import java.sql.CallableStatement
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.SQLFeatureNotSupportedException
import javax.sql.DataSource

/**
 * THE CONNECTIONS jOOQ WRITES BYTES BY: as bytes, not as a LOB. jOOQ binds every `byte[]` for H2 as
 * a Blob of the connection — `createBlob()`, then `setBlob` — and H2 writes such a Blob to its file
 * as a temporary LOB, kept till the connection is closed: every write of bytes — a model's scoring,
 * a page, a picture — written to the file twice, the copy left in it for as long as the pooled
 * connection lives, and no compaction gives its space back meanwhile. Of these connections the Blob
 * is an array in memory, set as the bytes it holds.
 */
internal class InlineBlobs(private val source: DataSource) : ConnectionProvider {

    override fun acquire(): Connection = proxy(source.connection)

    override fun release(connection: Connection) = connection.close()

    private fun proxy(connection: Connection): Connection {
        lateinit var self: Connection
        self = proxy(connection, Connection::class.java) { method, call ->
            if (method.name == "createBlob") Bytes() else when (val result = call()) {
                is CallableStatement -> statement(result, self, CallableStatement::class.java)
                is PreparedStatement -> statement(result, self, PreparedStatement::class.java)
                else -> result
            }
        }
        return self
    }

    /** The [statement], its Blobs of these connections set as bytes, its connection the one it was made by. */
    private fun <S : PreparedStatement> statement(statement: S, connection: Connection, type: Class<S>): S =
        proxy(statement, type) { method, call ->
            val args = call.args
            when {
                method.name == "setBlob" && args?.size == 2 && args[1] is Bytes -> statement.setBytes(args[0] as Int, (args[1] as Bytes).bytes)
                method.name == "getConnection" -> connection
                else -> call()
            }
        }

    /** The [target] as [type], every call to it made by [around]: the target itself to [java.sql.Wrapper.unwrap], H2's own classes reached through it. */
    private fun <T : Any> proxy(target: T, type: Class<T>, around: (Method, Call) -> Any?): T =
        type.cast(Proxy.newProxyInstance(javaClass.classLoader, arrayOf(type), InvocationHandler { _, method, args -> around(method, Call(target, method, args)) }))

    /** A call to the target as it was asked for. */
    private class Call(private val target: Any, private val method: Method, val args: Array<out Any?>?) {
        operator fun invoke(): Any? = try {
            method.invoke(target, *(args ?: emptyArray()))
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    /** A Blob of bytes in memory: what jOOQ writes into one and sets, nothing more. */
    private class Bytes : Blob {
        var bytes = ByteArray(0)
            private set

        override fun length() = bytes.size.toLong()

        override fun getBytes(pos: Long, length: Int): ByteArray = bytes.copyOfRange((pos - 1).toInt(), minOf(bytes.size, (pos - 1).toInt() + length))

        override fun getBinaryStream(): InputStream = ByteArrayInputStream(bytes)

        override fun setBytes(pos: Long, bytes: ByteArray): Int = setBytes(pos, bytes, 0, bytes.size)

        override fun setBytes(pos: Long, bytes: ByteArray, offset: Int, len: Int): Int {
            val at = (pos - 1).toInt()
            if (at + len > this.bytes.size) this.bytes = this.bytes.copyOf(at + len)
            System.arraycopy(bytes, offset, this.bytes, at, len)
            return len
        }

        override fun free() {}

        override fun position(pattern: ByteArray, start: Long): Long = throw SQLFeatureNotSupportedException()

        override fun position(pattern: Blob, start: Long): Long = throw SQLFeatureNotSupportedException()

        override fun setBinaryStream(pos: Long): OutputStream = throw SQLFeatureNotSupportedException()

        override fun truncate(len: Long) {
            bytes = bytes.copyOf(len.toInt())
        }

        override fun getBinaryStream(pos: Long, length: Long): InputStream = ByteArrayInputStream(bytes, (pos - 1).toInt(), length.toInt())
    }
}
