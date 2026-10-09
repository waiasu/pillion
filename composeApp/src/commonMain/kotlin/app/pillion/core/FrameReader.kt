package app.pillion.core

import app.pillion.protocol.NaviLiteCodec

/** A frame parsed off the wire. */
data class NaviFrameView(
    val frameType: Int,
    val serviceType: Int,
    val payloadDataType: Int,
    val payload: ByteArray,
)

/**
 * Reads complete NaviLite frames from a [ByteChannel]. Single responsibility: framing/resync.
 * It owns no transport and no protocol semantics beyond locating frame boundaries.
 */
class FrameReader(private val channel: ByteChannel) {
    private var buf = ByteArray(0)
    private val tmp = ByteArray(8192)

    fun next(): NaviFrameView {
        fill(NaviLiteCodec.HEADER_SIZE + NaviLiteCodec.CRC_SIZE)
        while (!NaviLiteCodec.hasMagicAt(buf, 0)) dropOne()
        val len = NaviLiteCodec.frameLengthAt(buf, 0)
        check(len >= NaviLiteCodec.HEADER_SIZE + NaviLiteCodec.CRC_SIZE) { "bad frame length $len" }
        fill(len)
        val view = NaviFrameView(
            frameType = NaviLiteCodec.frameTypeAt(buf, 0),
            serviceType = NaviLiteCodec.serviceTypeAt(buf, 0),
            payloadDataType = NaviLiteCodec.payloadDataTypeAt(buf, 0),
            payload = NaviLiteCodec.payloadAt(buf, 0),
        )
        buf = buf.copyOfRange(len, buf.size)
        return view
    }

    private fun fill(n: Int) {
        while (buf.size < n) {
            val r = channel.read(tmp)
            if (r < 0) error("channel closed")
            if (r > 0) buf += tmp.copyOf(r)
        }
    }

    private fun dropOne() {
        fill(4)
        buf = buf.copyOfRange(1, buf.size)
    }
}
