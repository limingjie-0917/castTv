package com.bd.casttv.dlna

import android.util.Log
import com.bd.casttv.dlna.SsdpDiagnostics
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.newFixedLengthResponse
import java.io.BufferedInputStream
import java.io.InputStream
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Embedded UPnP/DLNA HTTP server (NanoHTTPD).
 *
 * Responsibilities:
 *  - Serve the device description + the three SCPD documents (GET).
 *  - Handle SOAP control requests for AVTransport / RenderingControl /
 *    ConnectionManager (POST).
 *  - Handle GENA SUBSCRIBE / UNSUBSCRIBE.
 *
 * ── Why the custom [createClientHandler] override ──────────────────────────
 * NanoHTTPD 2.3.1's [NanoHTTPD.Method] enum does NOT contain SUBSCRIBE /
 * UNSUBSCRIBE, so its request line parser throws BAD_REQUEST for GENA and
 * serve() is never reached. We therefore intercept every accepted socket,
 * peek the HTTP verb on a mark/reset-able stream, answer GENA verbs directly,
 * and hand ordinary GET/POST connections back to NanoHTTPD unchanged. This
 * keeps the entire DLNA endpoint (HTTP + SOAP + GENA) on a single port.
 */
class DlnaHttpServer(
    port: Int,
    private val identityProvider: () -> DeviceIdentity,
    private val udnProvider: () -> String,
    private val controller: PlaybackController = PlaybackController,
    private val genaEventManager: GenaEventManager? = null
) : NanoHTTPD(port) {

    private val httpDateFormat = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)

    // ------------------------------------------------------------------
    // GENA interception at the socket layer (see class kdoc)
    // ------------------------------------------------------------------
    override fun createClientHandler(finalAccept: Socket, inputStream: InputStream): NanoHTTPD.ClientHandler {
        val buffered = BufferedInputStream(inputStream, 16 * 1024)
        try {
            buffered.mark(16 * 1024)
            val method = peekMethod(buffered)
            buffered.reset()
            if (method == "SUBSCRIBE" || method == "UNSUBSCRIBE") {
                // Handle GENA inline (fast) and close the connection ourselves.
                handleGena(finalAccept, buffered, method)
                try { finalAccept.close() } catch (_: Exception) {}
                // The returned handler runs against a now-closed socket and
                // terminates quietly inside NanoHTTPD's own try/catch.
            }
        } catch (_: Exception) {
            // Fall through: let NanoHTTPD attempt normal handling.
        }
        return super.createClientHandler(finalAccept, buffered)
    }

    /** Reads the leading HTTP verb token without consuming past mark limit. */
    private fun peekMethod(input: InputStream): String {
        val sb = StringBuilder()
        var c = input.read()
        var guard = 0
        while (c != -1 && c != ' '.code && c != '\r'.code && c != '\n'.code && guard < 32) {
            sb.append(c.toChar())
            c = input.read()
            guard++
        }
        return sb.toString().uppercase(Locale.US)
    }

    /** Handles GENA SUBSCRIBE / UNSUBSCRIBE with bounded header read on the accept thread. */
    private fun handleGena(socket: Socket, input: InputStream, method: String) {
        val previousTimeout = try { socket.soTimeout } catch (_: Exception) { 0 }
        try { socket.soTimeout = GENA_READ_TIMEOUT_MS } catch (_: Exception) {}
        val headers = mutableMapOf<String, String>()
        var path = ""
        try {
            val reader = input.bufferedReader(Charsets.UTF_8)
            val requestLine = reader.readLine().orEmpty()
            path = requestLine.split(' ').getOrNull(1).orEmpty()
            var guard = 0
            while (guard < MAX_HEADER_LINES) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx > 0) headers[line.substring(0, idx).trim().lowercase(Locale.US)] = line.substring(idx + 1).trim()
                guard++
            }
        } catch (_: Exception) { /* respond with 412 below if required */ }
        try { socket.soTimeout = previousTimeout } catch (_: Exception) {}

        val fromIp = socket.inetAddress?.hostAddress.orEmpty()
        val service = serviceForEventPath(path)
        val callback = headers["callback"].orEmpty()
        val nt = headers["nt"].orEmpty()
        val sid = headers["sid"].orEmpty()
        val timeoutSeconds = parseTimeoutSeconds(headers["timeout"].orEmpty())

        val response = when {
            service.isBlank() -> GenaResponse(412, "Precondition Failed", emptyMap())
            method == "SUBSCRIBE" && callback.isNotBlank() && nt.equals("upnp:event", ignoreCase = true) -> {
                val manager = genaEventManager
                val info = manager?.subscribe(callback, service, timeoutSeconds, initialLastChangeFor(service))
                if (info != null) {
                    GenaResponse(200, "OK", mapOf("SID" to info.sid, "TIMEOUT" to "Second-$timeoutSeconds"))
                } else GenaResponse(412, "Precondition Failed", emptyMap())
            }
            method == "SUBSCRIBE" && sid.isNotBlank() && callback.isBlank() -> {
                val info = genaEventManager?.renew(sid, timeoutSeconds)
                if (info != null) GenaResponse(200, "OK", mapOf("SID" to info.sid, "TIMEOUT" to "Second-$timeoutSeconds"))
                else GenaResponse(412, "Precondition Failed", emptyMap())
            }
            method == "UNSUBSCRIBE" && sid.isNotBlank() -> {
                if (genaEventManager?.unsubscribe(sid) == true) GenaResponse(200, "OK", emptyMap())
                else GenaResponse(412, "Precondition Failed", emptyMap())
            }
            else -> GenaResponse(412, "Precondition Failed", emptyMap())
        }

        try {
            val out = socket.getOutputStream()
            val sb = StringBuilder()
            sb.append("HTTP/1.1 ").append(response.code).append(' ').append(response.reason).append("\r\n")
            sb.append("DATE: ").append(httpDate()).append("\r\n")
            sb.append("SERVER: Android/9 UPnP/1.0 CastTV/1.0\r\n")
            response.headers.forEach { (k, v) -> sb.append(k).append(": ").append(v).append("\r\n") }
            sb.append("CONTENT-LENGTH: 0\r\n")
            sb.append("Connection: close\r\n")
            sb.append("\r\n")
            out.write(sb.toString().toByteArray(Charsets.UTF_8))
            out.flush()
            try {
                SsdpDiagnostics.logGena(method, fromIp, callback, nt, "${response.code} ${response.reason}")
                SsdpDiagnostics.logServiceHealth("GENA", SsdpDiagnostics.ServiceHealthEvent.Level.INFO, method.lowercase(Locale.US), "service=$service status=${response.code}")
            } catch (_: Throwable) {}
        } catch (e: Exception) {
            try { SsdpDiagnostics.logGena(method, fromIp, callback, nt, "ERROR ${e.javaClass.simpleName}") } catch (_: Throwable) {}
            Log.w(TAG, "GENA $method response failed", e)
        }
    }

    private data class GenaResponse(val code: Int, val reason: String, val headers: Map<String, String>)

    private fun serviceForEventPath(path: String): String = when (path.substringBefore('?')) {
        "/evt/AVTransport" -> UpnpXml.SVC_AVTRANSPORT
        "/evt/RenderingControl" -> UpnpXml.SVC_RENDERING
        else -> ""
    }

    private fun parseTimeoutSeconds(raw: String): Int {
        val value = raw.trim()
        if (value.equals("infinite", ignoreCase = true)) return DEFAULT_GENA_TIMEOUT_SECONDS
        return value.substringAfter("Second-", "").toIntOrNull()?.coerceIn(1, DEFAULT_GENA_TIMEOUT_SECONDS) ?: DEFAULT_GENA_TIMEOUT_SECONDS
    }

    private fun initialLastChangeFor(service: String): String = when (service) {
        UpnpXml.SVC_RENDERING -> controller.buildRenderingControlLastChange()
        else -> controller.buildAvTransportLastChange()
    }

    // ------------------------------------------------------------------
    // Normal GET / POST handling
    // ------------------------------------------------------------------
    override fun serve(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val uri = session.uri
        val fromIp = remoteIp(session).ifBlank { "?" }
        return try {
            when {
                uri == "/description.xml" -> {
                    // Diagnostics: record who fetched description.xml (used by apps after SSDP discovery).
                    try { SsdpDiagnostics.logDescriptionGet(fromIp) } catch (_: Throwable) {}
                    try { SsdpDiagnostics.logHttpGet(fromIp, uri, "200 OK") } catch (_: Throwable) {}
                    xml(UpnpXml.deviceDescription(identityProvider(), udnProvider()))
                }
                uri == "/scpd/AVTransport.xml" -> {
                    try { SsdpDiagnostics.logHttpGet(fromIp, uri, "200 OK") } catch (_: Throwable) {}
                    xml(UpnpXml.avTransportScpd())
                }
                uri == "/scpd/RenderingControl.xml" -> {
                    try { SsdpDiagnostics.logHttpGet(fromIp, uri, "200 OK") } catch (_: Throwable) {}
                    xml(UpnpXml.renderingControlScpd())
                }
                uri == "/scpd/ConnectionManager.xml" -> {
                    try { SsdpDiagnostics.logHttpGet(fromIp, uri, "200 OK") } catch (_: Throwable) {}
                    xml(UpnpXml.connectionManagerScpd())
                }
                uri == "/ctl/AVTransport" -> handleAvTransport(session)
                uri == "/ctl/RenderingControl" -> handleRenderingControl(session)
                uri == "/ctl/ConnectionManager" -> handleConnectionManager(session)
                uri == "/evt/AVTransport" || uri == "/evt/RenderingControl" -> {
                    // GENA verbs are intercepted before serve(); a plain GET here
                    // (rare probe) just returns OK.
                    try { SsdpDiagnostics.logHttpGet(fromIp, uri, "200 OK") } catch (_: Throwable) {}
                    newFixedLengthResponse(NanoHTTPD.Response.Status.OK, MIME_XML, "")
                }
                else -> {
                    try { SsdpDiagnostics.logHttpGet(fromIp, uri, "404 NOT_FOUND") } catch (_: Throwable) {}
                    newFixedLengthResponse(NanoHTTPD.Response.Status.NOT_FOUND, "text/plain", "Not Found")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "serve() failed for ${session.uri}", e)
            xml(UpnpXml.soapFault(501, e.message ?: "Action Failed"))
        }
    }

    // ------------------------------------------------------------------
    // AVTransport SOAP actions
    // ------------------------------------------------------------------
    private fun handleAvTransport(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val action = soapAction(session)
        logSoapEntry(session, "AVTransport", action)
        val body = readBody(session)
        val svc = UpnpXml.SVC_AVTRANSPORT
        return when (action) {
            "SetAVTransportURI" -> {
                val currentUri = xmlUnescape(extractTag(body, "CurrentURI"))
                val metadata = xmlUnescape(extractTag(body, "CurrentURIMetaData"))
                if (currentUri.isBlank()) {
                    // Don't silently drop: log the raw body so malformed / oddly
                    // namespaced payloads can be diagnosed from logcat.
                    Log.w(TAG, "SetAVTransportURI with blank CurrentURI; body=${body.take(512)}")
                    try { SsdpDiagnostics.logSoapResult("AVTransport", action, "ERROR blank CurrentURI", "body=${body.take(160)}") } catch (_: Throwable) {}
                }
                val title = extractTitle(metadata).ifBlank { deriveTitleFromUri(currentUri) }
                val hint = extractSourceHint(metadata, session)
                val artworkUrl = extractAlbumArtUri(metadata)
                val description = extractDescription(metadata)
                val resolution = extractResolution(metadata)
                val isDouyinCast = isDouyinCast(currentUri, metadata, hint, session)
                controller.onSetAvTransportUri(currentUri, title, hint, artworkUrl, description, resolution, isDouyinCast)
                try { SsdpDiagnostics.logSoapResult("AVTransport", action, "OK 200", "title=$title，uri=${currentUri.take(160)}") } catch (_: Throwable) {}
                xml(UpnpXml.soapResponse("SetAVTransportURI", svc, ""))
            }
            "SetNextAVTransportURI" -> {
                val nextUri = xmlUnescape(extractTag(body, "NextURI"))
                    .ifBlank { xmlUnescape(extractTag(body, "CurrentURI")) }
                val metadata = xmlUnescape(extractTag(body, "NextURIMetaData"))
                    .ifBlank { xmlUnescape(extractTag(body, "CurrentURIMetaData")) }
                if (nextUri.isBlank()) {
                    Log.w(TAG, "SetNextAVTransportURI with blank NextURI; body=${body.take(512)}")
                    try { SsdpDiagnostics.logSoapResult("AVTransport", action, "ERROR blank NextURI", "body=${body.take(160)}") } catch (_: Throwable) {}
                }
                val title = extractTitle(metadata).ifBlank { deriveTitleFromUri(nextUri) }
                val hint = extractSourceHint(metadata, session)
                val artworkUrl = extractAlbumArtUri(metadata)
                val description = extractDescription(metadata)
                val resolution = extractResolution(metadata)
                val isDouyinCast = isDouyinCast(nextUri, metadata, hint, session)
                controller.onSetNextAvTransportUri(nextUri, title, hint, artworkUrl, description, resolution, isDouyinCast)
                try { SsdpDiagnostics.logSoapResult("AVTransport", action, "OK 200", "title=$title，uri=${nextUri.take(160)}") } catch (_: Throwable) {}
                xml(UpnpXml.soapResponse("SetNextAVTransportURI", svc, ""))
            }
            "Play" -> { controller.play(); logSoapOk("AVTransport", action); xml(UpnpXml.soapResponse("Play", svc, "")) }
            "Next" -> {
                val switched = controller.next()
                logSoapOk("AVTransport", action, if (switched) "switched to NextURI" else "no NextURI cached")
                xml(UpnpXml.soapResponse("Next", svc, ""))
            }
            "Previous" -> {
                try { SsdpDiagnostics.logSoapResult("AVTransport", action, "ERROR SOAP_FAULT", "Transition not available") } catch (_: Throwable) {}
                xmlFault(701, "Transition not available")
            }
            "Pause" -> { controller.pause(); logSoapOk("AVTransport", action); xml(UpnpXml.soapResponse("Pause", svc, "")) }
            "Stop" -> { controller.stop(); logSoapOk("AVTransport", action); xml(UpnpXml.soapResponse("Stop", svc, "")) }
            "Seek" -> {
                val target = extractTag(body, "Target")
                controller.seek(parseTimeToMs(target))
                logSoapOk("AVTransport", action, "target=$target")
                xml(UpnpXml.soapResponse("Seek", svc, ""))
            }
            "GetTransportInfo" -> xml(
                UpnpXml.soapResponse("GetTransportInfo", svc,
                    "<CurrentTransportState>${controller.transportState.upnp}</CurrentTransportState>\n" +
                        "<CurrentTransportStatus>OK</CurrentTransportStatus>\n" +
                        "<CurrentSpeed>1</CurrentSpeed>\n")
            )
            "GetPositionInfo" -> {
                val dur = msToHms(controller.durationMs)
                val pos = msToHms(controller.positionMs)
                xml(UpnpXml.soapResponse("GetPositionInfo", svc,
                    "<Track>1</Track>\n" +
                        "<TrackDuration>$dur</TrackDuration>\n" +
                        "<TrackMetaData></TrackMetaData>\n" +
                        "<TrackURI>${UpnpXml.escape(controller.currentUri)}</TrackURI>\n" +
                        "<RelTime>$pos</RelTime>\n" +
                        "<AbsTime>$pos</AbsTime>\n" +
                        "<RelCount>2147483647</RelCount>\n" +
                        "<AbsCount>2147483647</AbsCount>\n"))
            }
            "GetMediaInfo" -> {
                val dur = msToHms(controller.durationMs)
                xml(UpnpXml.soapResponse("GetMediaInfo", svc,
                    "<NrTracks>1</NrTracks>\n" +
                        "<MediaDuration>$dur</MediaDuration>\n" +
                        "<CurrentURI>${UpnpXml.escape(controller.currentUri)}</CurrentURI>\n" +
                        "<CurrentURIMetaData></CurrentURIMetaData>\n" +
                        "<NextURI>${UpnpXml.escape(controller.nextUri)}</NextURI>\n" +
                        "<NextURIMetaData></NextURIMetaData>\n" +
                        "<PlayMedium>NETWORK</PlayMedium>\n" +
                        "<RecordMedium>NOT_IMPLEMENTED</RecordMedium>\n" +
                        "<WriteStatus>NOT_IMPLEMENTED</WriteStatus>\n"))
            }
            "GetDeviceCapabilities" -> xml(
                UpnpXml.soapResponse("GetDeviceCapabilities", svc,
                    "<PlayMedia>NETWORK,HDD</PlayMedia>\n" +
                        "<RecMedia>NOT_IMPLEMENTED</RecMedia>\n" +
                        "<RecQualityModes>NOT_IMPLEMENTED</RecQualityModes>\n")
            )
            "GetTransportSettings" -> xml(
                UpnpXml.soapResponse("GetTransportSettings", svc,
                    "<PlayMode>NORMAL</PlayMode>\n" +
                        "<RecQualityMode>NOT_IMPLEMENTED</RecQualityMode>\n")
            )
            "GetCurrentTransportActions" -> xml(
                UpnpXml.soapResponse("GetCurrentTransportActions", svc,
                    "<Actions>${controller.currentTransportActions()}</Actions>\n")
            )
            else -> { logSoapFault("DLNA", action); xml(UpnpXml.soapFault()) }
        }
    }

    // ------------------------------------------------------------------
    // RenderingControl SOAP actions
    // ------------------------------------------------------------------
    private fun handleRenderingControl(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val action = soapAction(session)
        logSoapEntry(session, "RenderingControl", action)
        val body = readBody(session)
        val svc = UpnpXml.SVC_RENDERING
        return when (action) {
            "GetVolume" -> xml(UpnpXml.soapResponse("GetVolume", svc,
                "<CurrentVolume>${controller.volume}</CurrentVolume>\n"))
            "SetVolume" -> {
                val v = extractTag(body, "DesiredVolume").toIntOrNull() ?: controller.volume
                controller.applyVolume(v)
                xml(UpnpXml.soapResponse("SetVolume", svc, ""))
            }
            "GetMute" -> xml(UpnpXml.soapResponse("GetMute", svc,
                "<CurrentMute>${if (controller.mute) 1 else 0}</CurrentMute>\n"))
            "SetMute" -> {
                val raw = extractTag(body, "DesiredMute")
                controller.applyMute(raw == "1" || raw.equals("true", true))
                xml(UpnpXml.soapResponse("SetMute", svc, ""))
            }
            "ListPresets" -> xml(UpnpXml.soapResponse("ListPresets", svc,
                "<CurrentPresetNameList>FactoryDefaults</CurrentPresetNameList>\n"))
            else -> { logSoapFault("DLNA", action); xml(UpnpXml.soapFault()) }
        }
    }

    // ------------------------------------------------------------------
    // ConnectionManager SOAP actions
    // ------------------------------------------------------------------
    private fun handleConnectionManager(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val action = soapAction(session)
        logSoapEntry(session, "ConnectionManager", action)
        val svc = UpnpXml.SVC_CONNMGR
        return when (action) {
            "GetProtocolInfo" -> { logSoapOk("ConnectionManager", action, "sink=${SINK_PROTOCOL_INFO.take(180)}"); xml(UpnpXml.soapResponse("GetProtocolInfo", svc,
                "<Source></Source>\n" +
                    "<Sink>${UpnpXml.escape(SINK_PROTOCOL_INFO)}</Sink>\n")) }
            "GetCurrentConnectionIDs" -> xml(UpnpXml.soapResponse("GetCurrentConnectionIDs", svc,
                "<ConnectionIDs>0</ConnectionIDs>\n"))
            "GetCurrentConnectionInfo" -> xml(UpnpXml.soapResponse("GetCurrentConnectionInfo", svc,
                "<RcsID>0</RcsID>\n" +
                    "<AVTransportID>0</AVTransportID>\n" +
                    "<ProtocolInfo></ProtocolInfo>\n" +
                    "<PeerConnectionManager></PeerConnectionManager>\n" +
                    "<PeerConnectionID>-1</PeerConnectionID>\n" +
                    "<Direction>Input</Direction>\n" +
                    "<Status>OK</Status>\n"))
            else -> { logSoapFault("DLNA", action); xml(UpnpXml.soapFault()) }
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------
    private fun xml(content: String): NanoHTTPD.Response =
        newFixedLengthResponse(NanoHTTPD.Response.Status.OK, MIME_XML, content).apply {
            // DLNA control points (notably Douyin) may retry discovery when HTTP
            // metadata responses look transient. Make descriptor/SCPD/SOAP
            // responses explicit and close each short request cleanly instead of
            // relying on NanoHTTPD's default keep-alive behaviour.
            addHeader("Cache-Control", "max-age=1800")
            addHeader("Connection", "close")
            addHeader("EXT", "")
        }

    private fun xmlFault(errorCode: Int, description: String): NanoHTTPD.Response =
        newFixedLengthResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR, MIME_XML, UpnpXml.soapFault(errorCode, description)).apply {
            addHeader("Connection", "close")
            addHeader("EXT", "")
        }

    private fun soapAction(session: NanoHTTPD.IHTTPSession): String {
        val raw = session.headers["soapaction"] ?: return ""
        // Format: "urn:...:service:AVTransport:1#SetAVTransportURI"
        val cleaned = raw.trim().trim('"')
        return cleaned.substringAfterLast('#')
    }

    private fun logSoapEntry(session: NanoHTTPD.IHTTPSession, service: String, action: String) {
        val fromIp = remoteIp(session).ifBlank { "?" }
        val userAgent = session.headers["user-agent"].orEmpty()
        SsdpDiagnostics.logSoapAction(service, action, fromIp, userAgent)
    }

    private fun logSoapOk(service: String, action: String, detail: String = "") {
        try { SsdpDiagnostics.logSoapResult(service, action, "OK 200", detail) } catch (_: Throwable) {}
    }

    private fun logSoapFault(service: String, action: String) {
        try { SsdpDiagnostics.logSoapResult(service, action, "ERROR SOAP_FAULT", "unsupported action") } catch (_: Throwable) {}
    }

    private fun remoteIp(session: NanoHTTPD.IHTTPSession): String {
        return try {
            val m = session.javaClass.getMethod("getRemoteIpAddress")
            (m.invoke(session) as? String).orEmpty()
        } catch (_: Throwable) {
            ""
        }
    }

    private fun readBody(session: NanoHTTPD.IHTTPSession): String {
        val files = HashMap<String, String>()
        return try {
            session.parseBody(files)
            files["postData"] ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    private fun httpDate(): String = httpDateFormat.format(java.util.Date())

    companion object {
        private const val TAG = "DlnaHttpServer"
        /** Bounds the GENA header drain on the accept thread. */
        private const val MAX_HEADER_LINES = 64
        /** GENA SUBSCRIBE header read timeout. Was 300ms — too short for Wi-Fi
         *  latency, causing the control point's event subscription to fail and
         *  triggering "connection interrupted" after device selection. 5s gives
         *  plenty of room without blocking the accept thread excessively. */
        private const val GENA_READ_TIMEOUT_MS = 5000
        private const val DEFAULT_GENA_TIMEOUT_SECONDS = 1800

        const val MIME_XML = "text/xml; charset=\"utf-8\""

        /** Sink protocol info advertised to control points (broad wildcard + common types). */
        const val SINK_PROTOCOL_INFO =
            "http-get:*:*:*," +
                "http-get:*:video/*:*," +
                "http-get:*:video/mp4:DLNA.ORG_OP=01;DLNA.ORG_FLAGS=01700000000000000000000000000000," +
                "http-get:*:video/x-matroska:*," +
                "http-get:*:video/mpeg:*," +
                "http-get:*:video/avi:*," +
                "http-get:*:video/quicktime:*," +
                "http-get:*:video/hevc:DLNA.ORG_OP=01;DLNA.ORG_FLAGS=01700000000000000000000000000000," +
                "http-get:*:video/H265:*," +
                "http-get:*:video/H264:*," +
                "http-get:*:video/mp2t:*," +
                "http-get:*:video/webm:*," +
                "http-get:*:video/3gpp:*," +
                "http-get:*:video/x-flv:*," +
                "http-get:*:application/vnd.apple.mpegurl:*," +
                "http-get:*:application/x-mpegURL:*," +
                "http-get:*:application/dash+xml:*," +
                "http-get:*:audio/*:*," +
                "http-get:*:audio/mpeg:*," +
                "http-get:*:audio/mp4:*," +
                "http-get:*:audio/x-flac:*," +
                "http-get:*:image/*:*"

        /** Extracts the inner text of <Tag> or <ns:Tag> (DOTALL). */
        fun extractTag(body: String, tag: String): String {
            val regex = Regex("<(?:[\\w-]+:)?$tag\\b[^>]*>(.*?)</(?:[\\w-]+:)?$tag>",
                setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            return regex.find(body)?.groupValues?.get(1)?.trim() ?: ""
        }

        /** Parses <dc:title> from a (already-unescaped) DIDL-Lite metadata blob. */
        fun extractTitle(metadata: String): String {
            if (metadata.isBlank()) return ""
            return extractTag(metadata, "title")
        }

        /** Best-effort sender hint: DIDL creator, else the SOAP User-Agent. */
        fun extractSourceHint(metadata: String, session: NanoHTTPD.IHTTPSession): String {
            val creator = extractTag(metadata, "creator")
            if (creator.isNotBlank()) return creator
            val ua = session.headers["user-agent"].orEmpty()
            return ua.substringBefore('/').substringBefore(' ').trim()
        }

        fun isDouyinCast(uri: String, metadata: String, hint: String, session: NanoHTTPD.IHTTPSession): Boolean {
            val ua = session.headers["user-agent"].orEmpty()
            val text = listOf(uri, metadata, hint, ua).joinToString("\n").lowercase()
            val markers = listOf(
                "douyin",
                "抖音",
                "aweme",
                "amemv",
                "snssdk1128",
                "iesdouyin",
                "douyinvod",
                "douyinpic",
                "douyinstatic"
            )
            return markers.any { it in text }
        }

        /**
         * DIDL-Lite 元数据中的封面 URL（标准字段 `<upnp:albumArtURI>`）。
         * DLNA 播控端（爱奇艺 / 优酷 / 腾讯 / 芒果 / QQ音乐 等）在 `SetAVTransportURI` 的
         * `CurrentURIMetaData` 中通常会带上该字段，用于展示节目封面。返回 http/https 绝对 URL
         * 时才认为有效，避免相对路径或 `res` 内嵌 base64 数据。
         */
        fun extractAlbumArtUri(metadata: String): String {
            if (metadata.isBlank()) return ""
            val raw = extractTag(metadata, "albumArtURI")
            if (raw.isBlank()) return ""
            val url = xmlUnescape(raw).trim()
            return if (url.startsWith("http://", ignoreCase = true) ||
                url.startsWith("https://", ignoreCase = true)
            ) url else ""
        }

        fun extractDescription(metadata: String): String {
            if (metadata.isBlank()) return ""
            return extractTag(metadata, "longDescription")
                .ifBlank { extractTag(metadata, "description") }
                .let { xmlUnescape(it).trim() }
        }

        fun extractResolution(metadata: String): String {
            if (metadata.isBlank()) return ""
            val regex = Regex("<(?:[\\w-]+:)?res\\b[^>]*\\sresolution=[\"']([^\"']+)[\"'][^>]*>",
                setOf(RegexOption.IGNORE_CASE))
            val raw = regex.find(metadata)?.groupValues?.get(1)?.trim().orEmpty()
            if (raw.isBlank()) return ""
            val parts = raw.lowercase(Locale.US).split('x')
            if (parts.size != 2) return "($raw)"
            val width = parts[0].trim().toIntOrNull()
            val height = parts[1].trim().toIntOrNull()
            return when {
                width == 3840 && height == 2160 -> "(4K)"
                width == 1920 && height == 1080 -> "(1080P)"
                width == 1280 && height == 720 -> "(720P)"
                else -> "($raw)"
            }
        }

        fun deriveTitleFromUri(uri: String): String {
            val name = uri.substringAfterLast('/').substringBefore('?')
            return if (name.isBlank()) "投屏内容" else name
        }

        /** Unescape XML entities (metadata / URIs arrive escaped inside SOAP). */
        fun xmlUnescape(s: String): String = s
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&#39;", "'")
            .replace("&amp;", "&")

        /** hh:mm:ss (or hh:mm:ss with REL_TIME) -> milliseconds. */
        fun parseTimeToMs(time: String): Long {
            val t = time.trim()
            if (t.isEmpty()) return 0
            // Support "hh:mm:ss" and "hh:mm:ss.mmm"
            val parts = t.split(":")
            return try {
                when (parts.size) {
                    3 -> {
                        val h = parts[0].toLong()
                        val m = parts[1].toLong()
                        val s = parts[2].toDouble()
                        ((h * 3600 + m * 60) * 1000 + (s * 1000).toLong())
                    }
                    2 -> {
                        val m = parts[0].toLong()
                        val s = parts[1].toDouble()
                        (m * 60 * 1000 + (s * 1000).toLong())
                    }
                    1 -> (parts[0].toDouble() * 1000).toLong()
                    else -> 0
                }
            } catch (_: Exception) { 0 }
        }

        /** milliseconds -> "hh:mm:ss". */
        fun msToHms(ms: Long): String {
            val totalSec = (ms.coerceAtLeast(0)) / 1000
            val h = totalSec / 3600
            val m = (totalSec % 3600) / 60
            val s = totalSec % 60
            return String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        }
    }
}
