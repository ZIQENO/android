package dev.ziqeno.android

import android.net.LocalSocket
import android.net.LocalSocketAddress
import io.grpc.CallOptions
import io.grpc.MethodDescriptor
import io.grpc.StatusRuntimeException
import io.grpc.okhttp.OkHttpChannelBuilder
import io.grpc.stub.ClientCalls
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

internal class RoutingControl(path: String) : AutoCloseable {
    private val channel = OkHttpChannelBuilder.forAddress("127.0.0.1", 1)
        .usePlaintext()
        .socketFactory(PrivateSocketFactory(path))
        .proxyDetector { null }
        .maxInboundMessageSize(512 * 1024)
        .build()

    fun select(balancer: String, target: String) {
        ClientCalls.blockingUnaryCall(
            channel, overrideMethod,
            CallOptions.DEFAULT.withWaitForReady().withDeadlineAfter(3, TimeUnit.SECONDS),
            field(1, balancer) + field(2, target),
        )
    }

    fun validateDomains(payload: String) {
        testPolicy(XrayEngine.domainValidation, payload)
    }

    fun selectDomains(payload: String) {
        try {
            select(XrayEngine.domainPolicy, payload)
        } catch (failure: StatusRuntimeException) {
            // The server may have written the policy before the reply timed out.
            try {
                testPolicy(XrayEngine.domainVerification, payload)
            } catch (_: StatusRuntimeException) {
                throw failure
            }
        }
    }

    private fun testPolicy(inbound: String, payload: String) {
        val context = field(1, inbound) + field(9, payload)
        ClientCalls.blockingUnaryCall(
            channel, testRouteMethod,
            CallOptions.DEFAULT.withWaitForReady().withDeadlineAfter(3, TimeUnit.SECONDS),
            field(1, context),
        )
    }

    fun traffic(): TunnelBytes = XrayTrafficStats.decode(
        ClientCalls.blockingUnaryCall(
            channel, statsMethod, CallOptions.DEFAULT.withDeadlineAfter(1, TimeUnit.SECONDS),
            field(1, "traffic>>>"),
        ),
    )

    override fun close() {
        channel.shutdownNow()
    }

    private companion object {
        val marshaller = object : MethodDescriptor.Marshaller<ByteArray> {
            override fun stream(value: ByteArray): InputStream = value.inputStream()
            override fun parse(stream: InputStream): ByteArray = stream.readBytes()
        }

        val overrideMethod = MethodDescriptor.newBuilder(marshaller, marshaller)
            .setType(MethodDescriptor.MethodType.UNARY)
            .setFullMethodName("xray.app.router.command.RoutingService/OverrideBalancerTarget")
            .build()

        val statsMethod = MethodDescriptor.newBuilder(marshaller, marshaller)
            .setType(MethodDescriptor.MethodType.UNARY)
            .setFullMethodName("xray.app.stats.command.StatsService/QueryStats")
            .build()

        val testRouteMethod = MethodDescriptor.newBuilder(marshaller, marshaller)
            .setType(MethodDescriptor.MethodType.UNARY)
            .setFullMethodName("xray.app.router.command.RoutingService/TestRoute")
            .build()

        fun field(number: Int, value: String): ByteArray = field(number, value.encodeToByteArray())

        fun field(number: Int, bytes: ByteArray): ByteArray = ByteArrayOutputStream().apply {
            write((number shl 3) or 2)
            var length = bytes.size
            while (length >= 128) {
                write((length and 127) or 128)
                length = length ushr 7
            }
            write(length)
            write(bytes)
        }.toByteArray()
    }
}

private class PrivateSocketFactory(private val path: String) : SocketFactory() {
    override fun createSocket(): Socket = PrivateSocket(path)
    override fun createSocket(host: String, port: Int): Socket = connect()
    override fun createSocket(host: InetAddress, port: Int): Socket = connect()
    override fun createSocket(host: String, port: Int, local: InetAddress, localPort: Int): Socket = connect()
    override fun createSocket(host: InetAddress, port: Int, local: InetAddress, localPort: Int): Socket = connect()

    private fun connect(): Socket = createSocket().apply { connect(InetSocketAddress("127.0.0.1", 1)) }
}

private class PrivateSocket(private val path: String) : Socket() {
    private val socket = LocalSocket()
    private val address = InetSocketAddress("127.0.0.1", 1)
    @Volatile private var closed = false

    override fun connect(endpoint: SocketAddress) {
        socket.connect(LocalSocketAddress(path, LocalSocketAddress.Namespace.FILESYSTEM))
    }

    override fun connect(endpoint: SocketAddress, timeout: Int) = connect(endpoint)
    override fun getInputStream() = socket.inputStream
    override fun getOutputStream() = socket.outputStream
    override fun getRemoteSocketAddress(): SocketAddress = address
    override fun getLocalSocketAddress(): SocketAddress = address
    override fun isConnected(): Boolean = socket.isConnected
    override fun isClosed(): Boolean = closed
    override fun setTcpNoDelay(on: Boolean) = Unit
    override fun setKeepAlive(on: Boolean) = Unit
    override fun setSoTimeout(timeout: Int) { socket.soTimeout = timeout }
    override fun getSoTimeout(): Int = socket.soTimeout
    override fun shutdownInput() = socket.shutdownInput()
    override fun shutdownOutput() = socket.shutdownOutput()

    override fun close() {
        closed = true
        socket.close()
    }
}

