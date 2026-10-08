package dev.obiente.nextcloudnative.app

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import okio.Buffer

/**
 * Minimal in-memory WebDAV account used by desktop sync engine tests.
 *
 * It implements only the guarded verbs the desktop executor issues for creates and replacements:
 * PROPFIND (depth 0 and 1), MKCOL, conditional PUT, and conditional or ranged GET. Every path is
 * synthetic and relative to the account file root.
 */
internal class DesktopFileSyncFakeDav(
    private val userId: String,
    rootDirectories: List<String>,
    private val onPut: (path: String) -> Unit = {},
) : Dispatcher() {
    private data class Node(val directory: Boolean, val bytes: ByteArray, val etag: String)

    private val nodes = ConcurrentHashMap<String, Node>()
    private val etags = AtomicLong()
    private val prefix = listOf("remote.php", "dav", "files", userId)
    private val methods = java.util.Collections.synchronizedList(ArrayList<String>())

    init {
        rootDirectories.forEach { nodes[it] = Node(true, ByteArray(0), nextEtag()) }
    }

    fun files(): Map<String, ByteArray> =
        nodes.filterValues { !it.directory }.mapValues { (_, node) -> node.bytes }

    fun directories(): Set<String> = nodes.filterValues(Node::directory).keys

    /** Every received HTTP method, in arrival order; unsupported verbs such as DELETE answer 405. */
    fun requestMethods(): List<String> = synchronized(methods) { methods.toList() }

    override fun dispatch(request: RecordedRequest): MockResponse {
        methods += request.method.toString()
        val segments = request.url.pathSegments.filter(String::isNotEmpty)
        if (segments.size < prefix.size || segments.take(prefix.size) != prefix) return status(404)
        val path = segments.drop(prefix.size).joinToString("/")
        return synchronized(this) {
            when (request.method) {
                "PROPFIND" -> propfind(path, request.headers["Depth"] ?: "1")
                "MKCOL" -> mkcol(path)
                "PUT" -> put(path, request)
                "GET" -> get(path, request)
                else -> status(405)
            }
        }
    }

    private fun propfind(path: String, depth: String): MockResponse {
        val node = nodes[path] ?: return status(404)
        val children = if (node.directory && depth != "0") {
            nodes.keys.filter { it.substringBeforeLast('/', "") == path && it != path }.sorted()
        } else {
            emptyList()
        }
        val body = buildString {
            append("<d:multistatus xmlns:d=\"DAV:\" xmlns:oc=\"http://owncloud.org/ns\">")
            (listOf(path) + children).forEach { item -> append(davResponse(item, nodes.getValue(item))) }
            append("</d:multistatus>")
        }
        return MockResponse.Builder().code(207).addHeader("Content-Type", "application/xml").body(body).build()
    }

    private fun davResponse(path: String, node: Node): String {
        // Synthetic test names are URL-safe, so hrefs need no percent-encoding.
        val href = "/" + (prefix + path.split('/')).joinToString("/") + if (node.directory) "/" else ""
        val type = if (node.directory) "<d:resourcetype><d:collection/></d:resourcetype>" else "<d:resourcetype/>"
        val length = if (node.directory) "" else "<d:getcontentlength>${node.bytes.size}</d:getcontentlength>"
        val permissions = if (node.directory) "<oc:permissions>RGDNVCK</oc:permissions>" else ""
        return "<d:response><d:href>$href</d:href><d:propstat><d:prop>" +
            "<d:getetag>${node.etag}</d:getetag>$length$type$permissions" +
            "</d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"
    }

    private fun mkcol(path: String): MockResponse {
        if (nodes.containsKey(path)) return status(405)
        if (nodes[path.substringBeforeLast('/', "")]?.directory != true) return status(409)
        nodes[path] = Node(true, ByteArray(0), nextEtag())
        return status(201)
    }

    private fun put(path: String, request: RecordedRequest): MockResponse {
        val current = nodes[path]
        if (request.headers["If-None-Match"] == "*" && current != null) return status(412)
        request.headers["If-Match"]?.let { expected -> if (current?.etag != expected) return status(412) }
        if (current?.directory == true) return status(409)
        if (nodes[path.substringBeforeLast('/', "")]?.directory != true) return status(409)
        val etag = nextEtag()
        nodes[path] = Node(false, request.body?.toByteArray() ?: ByteArray(0), etag)
        onPut(path)
        return MockResponse.Builder().code(if (current == null) 201 else 204).addHeader("ETag", etag).build()
    }

    private fun get(path: String, request: RecordedRequest): MockResponse {
        val node = nodes[path]?.takeIf { !it.directory } ?: return status(404)
        request.headers["If-Match"]?.let { expected -> if (node.etag != expected) return status(412) }
        val range = request.headers["Range"]?.removePrefix("bytes=")?.split('-')
        if (range != null) {
            val start = range[0].toInt()
            val end = range[1].toInt()
            return MockResponse.Builder().code(206).addHeader("ETag", node.etag)
                .addHeader("Content-Range", "bytes $start-$end/${node.bytes.size}")
                .body(Buffer().write(node.bytes.copyOfRange(start, end + 1))).build()
        }
        return MockResponse.Builder().code(200).addHeader("ETag", node.etag)
            .body(Buffer().write(node.bytes)).build()
    }

    private fun status(code: Int): MockResponse = MockResponse.Builder().code(code).build()

    private fun nextEtag(): String = "\"etag-${etags.incrementAndGet()}\""
}
