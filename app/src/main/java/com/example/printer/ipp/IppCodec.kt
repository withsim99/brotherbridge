package com.example.printer.ipp

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

object IppConstants {
    // IPP Operation IDs (RFC 8011)
    const val OP_PRINT_JOB = 0x0002
    const val OP_VALIDATE_JOB = 0x0004
    const val OP_CREATE_JOB = 0x0005
    const val OP_SEND_DOCUMENT = 0x0006
    const val OP_CANCEL_JOB = 0x0008
    const val OP_GET_JOB_ATTRIBUTES = 0x0009
    const val OP_GET_JOBS = 0x000A
    const val OP_GET_PRINTER_ATTRIBUTES = 0x000B

    // IPP Status Codes (RFC 8011)
    const val STATUS_SUCCESSFUL_OK = 0x0000
    const val STATUS_OK_IGNORED_OR_SUBSTITUTED = 0x0001
    const val STATUS_CLIENT_BAD_REQUEST = 0x0400
    const val STATUS_CLIENT_NOT_FOUND = 0x0406
    const val STATUS_CLIENT_REQUEST_ENTITY_TOO_LARGE = 0x0408
    const val STATUS_CLIENT_DOCUMENT_FORMAT_NOT_SUPPORTED = 0x040A
    const val STATUS_SERVER_INTERNAL_ERROR = 0x0500
    const val STATUS_SERVER_OPERATION_NOT_SUPPORTED = 0x0501
    const val STATUS_SERVER_VERSION_NOT_SUPPORTED = 0x0503
    const val STATUS_SERVER_BUSY = 0x0507

    // Delimiter Tags (RFC 8010)
    const val TAG_OPERATION_ATTRIBUTES = 0x01
    const val TAG_JOB_ATTRIBUTES = 0x02
    const val TAG_END_OF_ATTRIBUTES = 0x03
    const val TAG_PRINTER_ATTRIBUTES = 0x04
    const val TAG_UNSUPPORTED_ATTRIBUTES = 0x05

    // Out-of-Band & Value Tags (RFC 8010)
    const val TAG_UNSUPPORTED = 0x10
    const val TAG_UNKNOWN = 0x12
    const val TAG_NO_VALUE = 0x13
    const val TAG_INTEGER = 0x21
    const val TAG_BOOLEAN = 0x22
    const val TAG_ENUM = 0x23
    const val TAG_RESOLUTION = 0x32
    const val TAG_RANGE_OF_INTEGER = 0x33
    const val TAG_BEGIN_COLLECTION = 0x34
    const val TAG_TEXT_WITH_LANGUAGE = 0x35
    const val TAG_NAME_WITH_LANGUAGE = 0x36
    const val TAG_END_COLLECTION = 0x37
    const val TAG_TEXT_WITHOUT_LANGUAGE = 0x41
    const val TAG_NAME_WITHOUT_LANGUAGE = 0x42
    const val TAG_KEYWORD = 0x44
    const val TAG_URI = 0x45
    const val TAG_CHARSET = 0x47
    const val TAG_NATURAL_LANGUAGE = 0x48
    const val TAG_MIME_MEDIA_TYPE = 0x49
    const val TAG_MEMBER_ATTR_NAME = 0x4A
    const val RESOLUTION_UNIT_DPI = 0x03
    const val RESOLUTION_UNIT_DPCM = 0x04

    fun operationName(opId: Int): String = when (opId) {
        OP_PRINT_JOB -> "Print-Job"
        OP_VALIDATE_JOB -> "Validate-Job"
        OP_CREATE_JOB -> "Create-Job"
        OP_SEND_DOCUMENT -> "Send-Document"
        OP_CANCEL_JOB -> "Cancel-Job"
        OP_GET_JOB_ATTRIBUTES -> "Get-Job-Attributes"
        OP_GET_JOBS -> "Get-Jobs"
        OP_GET_PRINTER_ATTRIBUTES -> "Get-Printer-Attributes"
        else -> String.format("0x%04X", opId)
    }
}

data class IppAttribute(
    val tag: Int,
    val name: String,
    val stringValue: String? = null,
    val intValue: Int? = null,
    val boolValue: Boolean? = null,
    val rangeLower: Int? = null,
    val rangeUpper: Int? = null,
    val resolutionDpi: Int? = null,
    val collectionValue: Map<String, List<IppAttribute>>? = null,
)

data class IppRequest(
    val majorVersion: Int,
    val minorVersion: Int,
    val operationId: Int,
    val requestId: Int,
    val operationAttributes: Map<String, List<IppAttribute>>,
    val jobAttributes: Map<String, List<IppAttribute>>,
    val documentFile: File?,
    val documentSizeBytes: Long,
) {
    fun getStringAttr(name: String): String? =
        operationAttributes[name]?.firstOrNull()?.stringValue
            ?: jobAttributes[name]?.firstOrNull()?.stringValue

    fun getStringListAttr(name: String): List<String> =
        (operationAttributes[name] ?: jobAttributes[name])
            ?.mapNotNull { it.stringValue?.takeIf { s -> s.isNotEmpty() } }
            .orEmpty()

    fun getIntAttr(name: String): Int? =
        jobAttributes[name]?.firstOrNull()?.intValue
            ?: operationAttributes[name]?.firstOrNull()?.intValue

    fun getResolutionDpi(name: String): Int? =
        jobAttributes[name]?.firstOrNull()?.resolutionDpi
            ?: operationAttributes[name]?.firstOrNull()?.resolutionDpi

    fun getBoolAttr(name: String): Boolean? =
        jobAttributes[name]?.firstOrNull()?.boolValue
            ?: operationAttributes[name]?.firstOrNull()?.boolValue

    fun getCollectionAttr(name: String): Map<String, List<IppAttribute>>? =
        jobAttributes[name]?.firstOrNull()?.collectionValue
            ?: operationAttributes[name]?.firstOrNull()?.collectionValue

    fun getRangeListAttr(name: String): List<IntRange> {
        val attrs = jobAttributes[name] ?: operationAttributes[name] ?: return emptyList()
        return attrs.mapNotNull { attr ->
            val lower = attr.rangeLower ?: return@mapNotNull null
            val upper = attr.rangeUpper ?: lower
            if (lower > 0 && upper >= lower) lower..upper else null
        }
    }
}

class DocumentTooLargeException(message: String) : IllegalStateException(message)

class InsufficientSpoolSpaceException(message: String) : IllegalStateException(message)

object IppCodec {

    /** Hard upper limit per print document: 1 GiB (1,073,741,824 bytes). */
    const val MAX_DOCUMENT_BYTES = 1L * 1024L * 1024L * 1024L

    /** Equivalent upper limit in kilobytes (1,048,576 KiB) for IPP job-k-octets-supported. */
    const val MAX_DOCUMENT_K_OCTETS = 1_048_576

    /** Maximum impressions advertised in IPP job-impressions-supported. */
    const val MAX_JOB_IMPRESSIONS = 10_000

    /** Keep at least 64 MB free on device storage while spooling network documents. */
    private const val MIN_FREE_DISK_RESERVE_BYTES = 64L * 1024L * 1024L
    private const val DISK_CHECK_INTERVAL_BYTES = 8L * 1024L * 1024L

    /**
     * Decodes a binary IPP 1.1 / 2.0 request stream per RFC 8010.
     * Any document payload following the 0x03 End-Of-Attributes tag is streamed directly into
     * [spoolDir] using a 64 KB buffer so documents up to 1 GB never exhaust heap memory.
     */
    fun decodeRequest(
        input: InputStream,
        spoolDir: File?,
        maxDocumentBytes: Long = MAX_DOCUMENT_BYTES,
    ): IppRequest {
        val dataIn = DataInputStream(input)
        val major = dataIn.readUnsignedByte()
        val minor = dataIn.readUnsignedByte()
        val operationId = dataIn.readUnsignedShort()
        val requestId = dataIn.readInt()

        val opAttrs = LinkedHashMap<String, MutableList<IppAttribute>>()
        val jobAttrs = LinkedHashMap<String, MutableList<IppAttribute>>()
        var currentGroup: MutableMap<String, MutableList<IppAttribute>> = opAttrs
        var lastAttrName = ""

        loop@ while (true) {
            val tag = dataIn.read()
            if (tag == -1) break
            when (tag) {
                IppConstants.TAG_END_OF_ATTRIBUTES -> break@loop
                IppConstants.TAG_OPERATION_ATTRIBUTES -> {
                    currentGroup = opAttrs
                    lastAttrName = ""
                }
                IppConstants.TAG_JOB_ATTRIBUTES -> {
                    currentGroup = jobAttrs
                    lastAttrName = ""
                }
                IppConstants.TAG_PRINTER_ATTRIBUTES, IppConstants.TAG_UNSUPPORTED_ATTRIBUTES -> {
                    currentGroup = LinkedHashMap()
                    lastAttrName = ""
                }
                in 0x00..0x0F -> {
                    // Other delimiter group
                    currentGroup = LinkedHashMap()
                    lastAttrName = ""
                }
                else -> {
                    val nameLen = dataIn.readUnsignedShort()
                    val name = if (nameLen > 0) {
                        val nameBytes = ByteArray(nameLen)
                        dataIn.readFully(nameBytes)
                        String(nameBytes, Charsets.UTF_8).also { lastAttrName = it }
                    } else {
                        lastAttrName
                    }

                    val valLen = dataIn.readUnsignedShort()
                    val valBytes = ByteArray(valLen)
                    if (valLen > 0) dataIn.readFully(valBytes)

                    val attr = if (tag == IppConstants.TAG_BEGIN_COLLECTION) {
                        val members = readCollection(dataIn, depth = 0)
                        IppAttribute(tag = tag, name = name, collectionValue = members)
                    } else {
                        parseAttributeValue(tag, name, valBytes)
                    }
                    if (name.isNotEmpty()) {
                        currentGroup.getOrPut(name) { mutableListOf() }.add(attr)
                    }
                }
            }
        }

        // Stream any trailing document payload (PDF or Image) to a temp spool file
        var docFile: File? = null
        var docBytes = 0L
        if (spoolDir != null) {
            val probe = ByteArray(64 * 1024)
            val firstRead = input.read(probe)
            if (firstRead > 0) {
                spoolDir.mkdirs()
                if (spoolDir.usableSpace > 0L && spoolDir.usableSpace < MIN_FREE_DISK_RESERVE_BYTES) {
                    throw InsufficientSpoolSpaceException(
                        "Not enough free device storage to spool incoming print job (need > 64 MB free)",
                    )
                }
                val file = File.createTempFile("ipp_doc_", ".bin", spoolDir)
                var nextDiskCheckAt = DISK_CHECK_INTERVAL_BYTES
                try {
                    FileOutputStream(file).use { out ->
                        docBytes += firstRead
                        if (docBytes > maxDocumentBytes) {
                            throw DocumentTooLargeException(
                                "Document exceeds 1 GB (${maxDocumentBytes / (1024 * 1024)} MB) maximum print size limit",
                            )
                        }
                        out.write(probe, 0, firstRead)
                        while (true) {
                            val r = input.read(probe)
                            if (r <= 0) break
                            docBytes += r
                            if (docBytes > maxDocumentBytes) {
                                throw DocumentTooLargeException(
                                    "Document exceeds 1 GB (${maxDocumentBytes / (1024 * 1024)} MB) maximum print size limit",
                                )
                            }
                            if (docBytes >= nextDiskCheckAt) {
                                nextDiskCheckAt += DISK_CHECK_INTERVAL_BYTES
                                if (spoolDir.usableSpace > 0L && spoolDir.usableSpace < MIN_FREE_DISK_RESERVE_BYTES) {
                                    throw InsufficientSpoolSpaceException(
                                        "Device storage nearly full while receiving print job — aborted safely",
                                    )
                                }
                            }
                            out.write(probe, 0, r)
                        }
                    }
                    docFile = file
                } catch (t: Throwable) {
                    file.delete()
                    throw t
                }
            }
        }

        return IppRequest(
            majorVersion = major,
            minorVersion = minor,
            operationId = operationId,
            requestId = requestId,
            operationAttributes = opAttrs,
            jobAttributes = jobAttrs,
            documentFile = docFile,
            documentSizeBytes = docBytes,
        )
    }

    /**
     * Decodes an RFC 8010 IPP Collection value (everything between begCollection 0x34 and endCollection 0x37).
     * Supports nested collections such as `media-col` -> `media-size` -> `x-dimension`/`y-dimension`.
     */
    private fun readCollection(dataIn: DataInputStream, depth: Int): Map<String, List<IppAttribute>> {
        val members = LinkedHashMap<String, MutableList<IppAttribute>>()
        if (depth > 6) return members
        var currentMemberName = ""
        while (true) {
            val tag = dataIn.read()
            if (tag == -1) break
            val nameLen = dataIn.readUnsignedShort()
            if (nameLen > 0) {
                val ignoredName = ByteArray(nameLen)
                dataIn.readFully(ignoredName)
            }
            val valLen = dataIn.readUnsignedShort()
            val valBytes = ByteArray(valLen)
            if (valLen > 0) dataIn.readFully(valBytes)

            when (tag) {
                IppConstants.TAG_END_COLLECTION -> break
                IppConstants.TAG_MEMBER_ATTR_NAME -> {
                    currentMemberName = String(valBytes, Charsets.UTF_8)
                }
                IppConstants.TAG_BEGIN_COLLECTION -> {
                    val nested = readCollection(dataIn, depth + 1)
                    if (currentMemberName.isNotEmpty()) {
                        members.getOrPut(currentMemberName) { mutableListOf() }.add(
                            IppAttribute(tag = tag, name = currentMemberName, collectionValue = nested),
                        )
                    }
                }
                else -> {
                    if (currentMemberName.isNotEmpty()) {
                        members.getOrPut(currentMemberName) { mutableListOf() }.add(
                            parseAttributeValue(tag, currentMemberName, valBytes),
                        )
                    }
                }
            }
        }
        return members
    }

    private fun parseAttributeValue(tag: Int, name: String, bytes: ByteArray): IppAttribute {
        return when (tag) {
            IppConstants.TAG_INTEGER, IppConstants.TAG_ENUM -> {
                val value = if (bytes.size == 4) {
                    ((bytes[0].toInt() and 0xFF) shl 24) or
                        ((bytes[1].toInt() and 0xFF) shl 16) or
                        ((bytes[2].toInt() and 0xFF) shl 8) or
                        (bytes[3].toInt() and 0xFF)
                } else 0
                IppAttribute(tag = tag, name = name, intValue = value, stringValue = value.toString())
            }
            IppConstants.TAG_BOOLEAN -> {
                val b = bytes.isNotEmpty() && bytes[0].toInt() != 0
                IppAttribute(tag = tag, name = name, boolValue = b, stringValue = b.toString())
            }
            IppConstants.TAG_RESOLUTION -> {
                val crossFeed = if (bytes.size >= 4) {
                    ((bytes[0].toInt() and 0xFF) shl 24) or
                        ((bytes[1].toInt() and 0xFF) shl 16) or
                        ((bytes[2].toInt() and 0xFF) shl 8) or
                        (bytes[3].toInt() and 0xFF)
                } else 0
                val feed = if (bytes.size >= 8) {
                    ((bytes[4].toInt() and 0xFF) shl 24) or
                        ((bytes[5].toInt() and 0xFF) shl 16) or
                        ((bytes[6].toInt() and 0xFF) shl 8) or
                        (bytes[7].toInt() and 0xFF)
                } else crossFeed
                val units = if (bytes.size >= 9) bytes[8].toInt() and 0xFF else IppConstants.RESOLUTION_UNIT_DPI
                val raw = maxOf(crossFeed, feed)
                val dpi = if (units == IppConstants.RESOLUTION_UNIT_DPCM) {
                    kotlin.math.round(raw * 2.54).toInt()
                } else {
                    raw
                }
                IppAttribute(
                    tag = tag,
                    name = name,
                    intValue = dpi,
                    resolutionDpi = dpi,
                    stringValue = "${crossFeed}x${feed}dpi",
                )
            }
            IppConstants.TAG_RANGE_OF_INTEGER -> {
                val lower = if (bytes.size >= 4) {
                    ((bytes[0].toInt() and 0xFF) shl 24) or
                        ((bytes[1].toInt() and 0xFF) shl 16) or
                        ((bytes[2].toInt() and 0xFF) shl 8) or
                        (bytes[3].toInt() and 0xFF)
                } else 0
                val upper = if (bytes.size >= 8) {
                    ((bytes[4].toInt() and 0xFF) shl 24) or
                        ((bytes[5].toInt() and 0xFF) shl 16) or
                        ((bytes[6].toInt() and 0xFF) shl 8) or
                        (bytes[7].toInt() and 0xFF)
                } else lower
                IppAttribute(tag = tag, name = name, rangeLower = lower, rangeUpper = upper)
            }
            IppConstants.TAG_TEXT_WITH_LANGUAGE, IppConstants.TAG_NAME_WITH_LANGUAGE -> {
                val decoded = if (bytes.size >= 4) {
                    val langLen = ((bytes[0].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF)
                    val textLenOffset = 2 + langLen
                    if (textLenOffset + 2 <= bytes.size) {
                        val textLen = ((bytes[textLenOffset].toInt() and 0xFF) shl 8) or
                            (bytes[textLenOffset + 1].toInt() and 0xFF)
                        val textStart = textLenOffset + 2
                        val safeLen = textLen.coerceAtMost((bytes.size - textStart).coerceAtLeast(0))
                        String(bytes, textStart, safeLen, Charsets.UTF_8)
                    } else {
                        String(bytes, Charsets.UTF_8)
                    }
                } else {
                    String(bytes, Charsets.UTF_8)
                }
                IppAttribute(tag = tag, name = name, stringValue = decoded)
            }
            else -> {
                IppAttribute(tag = tag, name = name, stringValue = String(bytes, Charsets.UTF_8))
            }
        }
    }

    class ResponseBuilder(
        majorVersion: Int,
        minorVersion: Int,
        statusCode: Int,
        requestId: Int,
        naturalLanguage: String = "en",
    ) {
        private val buffer = ByteArrayOutputStream(1024)
        private val out = DataOutputStream(buffer)

        init {
            out.writeByte(majorVersion)
            out.writeByte(minorVersion)
            out.writeShort(statusCode)
            out.writeInt(requestId)
            // Mandatory Operation Attributes group per RFC 8011 section 3.1.4.2
            beginGroup(IppConstants.TAG_OPERATION_ATTRIBUTES)
            addString(IppConstants.TAG_CHARSET, "attributes-charset", "utf-8")
            addString(
                IppConstants.TAG_NATURAL_LANGUAGE,
                "attributes-natural-language",
                naturalLanguage.trim().lowercase().ifBlank { "en" },
            )
        }

        fun beginGroup(groupTag: Int): ResponseBuilder {
            out.writeByte(groupTag)
            return this
        }

        fun addString(tag: Int, name: String, value: String): ResponseBuilder {
            val nameBytes = name.toByteArray(Charsets.UTF_8)
            val valBytes = value.toByteArray(Charsets.UTF_8)
            out.writeByte(tag)
            out.writeShort(nameBytes.size)
            out.write(nameBytes)
            out.writeShort(valBytes.size)
            out.write(valBytes)
            return this
        }

        fun addStringList(tag: Int, name: String, values: List<String>): ResponseBuilder {
            values.forEachIndexed { idx, value ->
                addString(tag, if (idx == 0) name else "", value)
            }
            return this
        }

        fun addInt(tag: Int, name: String, value: Int): ResponseBuilder {
            val nameBytes = name.toByteArray(Charsets.UTF_8)
            out.writeByte(tag)
            out.writeShort(nameBytes.size)
            out.write(nameBytes)
            out.writeShort(4)
            out.writeInt(value)
            return this
        }

        fun addIntList(tag: Int, name: String, values: List<Int>): ResponseBuilder {
            values.forEachIndexed { idx, value ->
                addInt(tag, if (idx == 0) name else "", value)
            }
            return this
        }

        fun addBoolean(name: String, value: Boolean): ResponseBuilder {
            val nameBytes = name.toByteArray(Charsets.UTF_8)
            out.writeByte(IppConstants.TAG_BOOLEAN)
            out.writeShort(nameBytes.size)
            out.write(nameBytes)
            out.writeShort(1)
            out.writeByte(if (value) 1 else 0)
            return this
        }

        fun addRangeOfInt(name: String, lower: Int, upper: Int): ResponseBuilder {
            val nameBytes = name.toByteArray(Charsets.UTF_8)
            out.writeByte(IppConstants.TAG_RANGE_OF_INTEGER)
            out.writeShort(nameBytes.size)
            out.write(nameBytes)
            out.writeShort(8)
            out.writeInt(lower)
            out.writeInt(upper)
            return this
        }

        fun addResolution(name: String, crossFeedDpi: Int, feedDpi: Int = crossFeedDpi): ResponseBuilder {
            val nameBytes = name.toByteArray(Charsets.UTF_8)
            out.writeByte(IppConstants.TAG_RESOLUTION)
            out.writeShort(nameBytes.size)
            out.write(nameBytes)
            out.writeShort(9)
            out.writeInt(crossFeedDpi)
            out.writeInt(feedDpi)
            out.writeByte(IppConstants.RESOLUTION_UNIT_DPI)
            return this
        }

        fun addResolutionList(name: String, dpis: List<Int>): ResponseBuilder {
            dpis.forEachIndexed { idx, dpi ->
                addResolution(if (idx == 0) name else "", dpi, dpi)
            }
            return this
        }

        fun beginCollection(name: String): ResponseBuilder {
            val nameBytes = name.toByteArray(Charsets.UTF_8)
            out.writeByte(IppConstants.TAG_BEGIN_COLLECTION)
            out.writeShort(nameBytes.size)
            out.write(nameBytes)
            out.writeShort(0)
            return this
        }

        fun beginMemberCollection(memberName: String): ResponseBuilder {
            writeMemberName(memberName)
            out.writeByte(IppConstants.TAG_BEGIN_COLLECTION)
            out.writeShort(0)
            out.writeShort(0)
            return this
        }

        fun addMemberInt(tag: Int, memberName: String, value: Int): ResponseBuilder {
            writeMemberName(memberName)
            out.writeByte(tag)
            out.writeShort(0)
            out.writeShort(4)
            out.writeInt(value)
            return this
        }

        fun addMemberString(tag: Int, memberName: String, value: String): ResponseBuilder {
            writeMemberName(memberName)
            val valBytes = value.toByteArray(Charsets.UTF_8)
            out.writeByte(tag)
            out.writeShort(0)
            out.writeShort(valBytes.size)
            out.write(valBytes)
            return this
        }

        fun endCollection(): ResponseBuilder {
            out.writeByte(IppConstants.TAG_END_COLLECTION)
            out.writeShort(0)
            out.writeShort(0)
            return this
        }

        private fun writeMemberName(memberName: String) {
            val bytes = memberName.toByteArray(Charsets.UTF_8)
            out.writeByte(IppConstants.TAG_MEMBER_ATTR_NAME)
            out.writeShort(0)
            out.writeShort(bytes.size)
            out.write(bytes)
        }

        fun build(): ByteArray {
            out.writeByte(IppConstants.TAG_END_OF_ATTRIBUTES)
            out.flush()
            return buffer.toByteArray()
        }
    }

    /**
     * Decoded binary IPP 1.1 / 2.0 response from a remote wireless IPP printer or PrintBridge server.
     */
    data class IppResponse(
        val majorVersion: Int,
        val minorVersion: Int,
        val statusCode: Int,
        val requestId: Int,
        val operationAttributes: Map<String, List<IppAttribute>>,
        val printerAttributes: Map<String, List<IppAttribute>>,
        val jobAttributes: Map<String, List<IppAttribute>>,
    ) {
        val isSuccess: Boolean get() = statusCode in 0x0000..0x00FF

        fun getStringAttr(name: String): String? =
            printerAttributes[name]?.firstOrNull()?.stringValue
                ?: jobAttributes[name]?.firstOrNull()?.stringValue
                ?: operationAttributes[name]?.firstOrNull()?.stringValue

        fun getStringListAttr(name: String): List<String> =
            (printerAttributes[name] ?: jobAttributes[name] ?: operationAttributes[name])
                ?.mapNotNull { it.stringValue }
                .orEmpty()

        fun getIntAttr(name: String): Int? =
            printerAttributes[name]?.firstOrNull()?.intValue
                ?: jobAttributes[name]?.firstOrNull()?.intValue
                ?: operationAttributes[name]?.firstOrNull()?.intValue

        fun getBoolAttr(name: String): Boolean? =
            printerAttributes[name]?.firstOrNull()?.boolValue
                ?: jobAttributes[name]?.firstOrNull()?.boolValue
                ?: operationAttributes[name]?.firstOrNull()?.boolValue
    }

    fun decodeResponse(input: InputStream): IppResponse {
        val dataIn = DataInputStream(input)
        val major = dataIn.readUnsignedByte()
        val minor = dataIn.readUnsignedByte()
        val statusCode = dataIn.readUnsignedShort()
        val requestId = dataIn.readInt()

        val opAttrs = LinkedHashMap<String, MutableList<IppAttribute>>()
        val printerAttrs = LinkedHashMap<String, MutableList<IppAttribute>>()
        val jobAttrs = LinkedHashMap<String, MutableList<IppAttribute>>()
        var currentGroup: MutableMap<String, MutableList<IppAttribute>> = opAttrs
        var lastAttrName = ""

        loop@ while (true) {
            val tag = dataIn.read()
            if (tag == -1) break
            when (tag) {
                IppConstants.TAG_END_OF_ATTRIBUTES -> break@loop
                IppConstants.TAG_OPERATION_ATTRIBUTES -> {
                    currentGroup = opAttrs
                    lastAttrName = ""
                }
                IppConstants.TAG_JOB_ATTRIBUTES -> {
                    currentGroup = jobAttrs
                    lastAttrName = ""
                }
                IppConstants.TAG_PRINTER_ATTRIBUTES -> {
                    currentGroup = printerAttrs
                    lastAttrName = ""
                }
                in 0x00..0x0F -> {
                    currentGroup = LinkedHashMap()
                    lastAttrName = ""
                }
                else -> {
                    val nameLen = dataIn.readUnsignedShort()
                    val name = if (nameLen > 0) {
                        val nameBytes = ByteArray(nameLen)
                        dataIn.readFully(nameBytes)
                        String(nameBytes, Charsets.UTF_8).also { lastAttrName = it }
                    } else {
                        lastAttrName
                    }

                    val valLen = dataIn.readUnsignedShort()
                    val valBytes = ByteArray(valLen)
                    if (valLen > 0) dataIn.readFully(valBytes)

                    val attr = if (tag == IppConstants.TAG_BEGIN_COLLECTION) {
                        val members = readCollection(dataIn, depth = 0)
                        IppAttribute(tag = tag, name = name, collectionValue = members)
                    } else {
                        parseAttributeValue(tag, name, valBytes)
                    }
                    if (name.isNotEmpty()) {
                        currentGroup.getOrPut(name) { mutableListOf() }.add(attr)
                    }
                }
            }
        }

        return IppResponse(
            majorVersion = major,
            minorVersion = minor,
            statusCode = statusCode,
            requestId = requestId,
            operationAttributes = opAttrs,
            printerAttributes = printerAttrs,
            jobAttributes = jobAttrs,
        )
    }

    /**
     * Builds binary RFC 8010/8011 IPP 2.0 client requests (Get-Printer-Attributes, Print-Job,
     * Get-Job-Attributes, Cancel-Job) to communicate with wireless PrintBridge or standard IPP printers.
     */
    class RequestBuilder(
        majorVersion: Int = 2,
        minorVersion: Int = 0,
        operationId: Int,
        requestId: Int,
    ) {
        private val buffer = ByteArrayOutputStream(512)
        private val out = DataOutputStream(buffer)

        init {
            out.writeByte(majorVersion)
            out.writeByte(minorVersion)
            out.writeShort(operationId)
            out.writeInt(requestId)
            beginGroup(IppConstants.TAG_OPERATION_ATTRIBUTES)
            addString(IppConstants.TAG_CHARSET, "attributes-charset", "utf-8")
            addString(IppConstants.TAG_NATURAL_LANGUAGE, "attributes-natural-language", "en")
        }

        fun beginGroup(groupTag: Int): RequestBuilder {
            out.writeByte(groupTag)
            return this
        }

        fun addString(tag: Int, name: String, value: String): RequestBuilder {
            val nameBytes = name.toByteArray(Charsets.UTF_8)
            val valBytes = value.toByteArray(Charsets.UTF_8)
            out.writeByte(tag)
            out.writeShort(nameBytes.size)
            out.write(nameBytes)
            out.writeShort(valBytes.size)
            out.write(valBytes)
            return this
        }

        fun addInt(tag: Int, name: String, value: Int): RequestBuilder {
            val nameBytes = name.toByteArray(Charsets.UTF_8)
            out.writeByte(tag)
            out.writeShort(nameBytes.size)
            out.write(nameBytes)
            out.writeShort(4)
            out.writeInt(value)
            return this
        }

        fun addBoolean(name: String, value: Boolean): RequestBuilder {
            val nameBytes = name.toByteArray(Charsets.UTF_8)
            out.writeByte(IppConstants.TAG_BOOLEAN)
            out.writeShort(nameBytes.size)
            out.write(nameBytes)
            out.writeShort(1)
            out.writeByte(if (value) 1 else 0)
            return this
        }

        fun addResolution(name: String, crossFeedDpi: Int, feedDpi: Int = crossFeedDpi): RequestBuilder {
            val nameBytes = name.toByteArray(Charsets.UTF_8)
            out.writeByte(IppConstants.TAG_RESOLUTION)
            out.writeShort(nameBytes.size)
            out.write(nameBytes)
            out.writeShort(9)
            out.writeInt(crossFeedDpi)
            out.writeInt(feedDpi)
            out.writeByte(IppConstants.RESOLUTION_UNIT_DPI)
            return this
        }

        fun buildHeader(): ByteArray {
            out.writeByte(IppConstants.TAG_END_OF_ATTRIBUTES)
            out.flush()
            return buffer.toByteArray()
        }
    }
}
