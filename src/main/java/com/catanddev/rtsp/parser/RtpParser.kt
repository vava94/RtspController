package com.catanddev.rtsp.parser

abstract class RtpParser {

    /**
     * Depacketize an RTP payload located at [data]\[[offset] until [offset]+[length]).
     * The offset overload lets callers pass a shared packet buffer without copying the payload.
     */
    abstract fun processRtpPacketAndGetNalUnit(
        data: ByteArray,
        offset: Int,
        length: Int,
        marker: Boolean
    ): ByteArray?

    /**
     * Backward-compatible overload for callers whose payload starts at index 0.
     */
    open fun processRtpPacketAndGetNalUnit(data: ByteArray, length: Int, marker: Boolean): ByteArray? =
        processRtpPacketAndGetNalUnit(data, 0, length, marker)

    // TODO Use already allocated buffer with RtpPacket.MAX_SIZE = 65507
    // Used only for fragmented packets
    protected val fragmentedBuffer = arrayOfNulls<ByteArray>(1024)
    protected var fragmentedBufferLength = 0
    protected var fragmentedPackets = 0

    protected fun writeNalPrefix0001(buffer: ByteArray) {
        buffer[0] = 0x00
        buffer[1] = 0x00
        buffer[2] = 0x00
        buffer[3] = 0x01
    }

    protected fun processSingleFramePacket(data: ByteArray, offset: Int, length: Int): ByteArray {
        return ByteArray(4 + length).apply {
            writeNalPrefix0001(this)
            System.arraycopy(data, offset, this, 4, length)
        }
    }

    protected fun processSingleFramePacket(data: ByteArray, length: Int): ByteArray =
        processSingleFramePacket(data, 0, length)

}