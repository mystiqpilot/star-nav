package tools.apachi.starlinknav.tools

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import tools.apachi.starlinknav.core.dish.DishClient
import tools.apachi.starlinknav.core.dish.FakeDevice
import tools.apachi.starlinknav.core.dish.GrpcFrames
import tools.apachi.starlinknav.core.dish.proto.DeviceRequest
import tools.apachi.starlinknav.core.dish.proto.DeviceResponse
import java.io.Closeable
import java.net.InetSocketAddress
import java.net.URLEncoder

/** Serves [sim] (a fake dish or a fake router) as gRPC-Web on HTTP/1.1, like the dish's port 9201 or the router's 9001. */
class FakeDishServer(private val sim: FakeDevice, bind: String = "127.0.0.1", port: Int = DishClient.WEB_PORT) : Closeable {
    private val server = HttpServer.create(InetSocketAddress(bind, port), 0)
    val port: Int get() = server.address.port

    init {
        server.createContext(DishClient.HANDLE_PATH) { ex ->
            try {
                ex.use { serve(it) }
            } catch (e: Exception) {
                System.err.println("fake dish: ${e.stackTraceToString()}")
            }
        }
        server.start()
    }

    private fun serve(ex: HttpExchange) {
        // A restarting dish takes no calls: close the connection without an answer.
        if (sim.isDown()) return
        val frames = GrpcFrames.decodeAll(ex.requestBody.readBytes())
        val req = DeviceRequest.ADAPTER.decode(GrpcFrames.firstDataPayload(frames) ?: ByteArray(0))
        val (resp, status) = sim.handle(req)
        ex.responseHeaders.add("Content-Type", "application/grpc-web+proto")
        if (resp == null) {
            ex.responseHeaders.add("grpc-status", status.first.toString())
            ex.responseHeaders.add("grpc-message", URLEncoder.encode(status.second, Charsets.UTF_8).replace("+", "%20"))
            ex.sendResponseHeaders(200, -1)
            return
        }
        val trailer = "grpc-status:0\r\ngrpc-message:\r\n".encodeToByteArray()
        val body = GrpcFrames.encode(DeviceResponse.ADAPTER.encode(resp)) +
            byteArrayOf(0x80.toByte(), 0, 0, 0, trailer.size.toByte()) + trailer
        ex.sendResponseHeaders(200, body.size.toLong())
        ex.responseBody.write(body)
    }

    override fun close() = server.stop(0)
}
