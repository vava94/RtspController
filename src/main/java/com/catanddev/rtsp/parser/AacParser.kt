package com.catanddev.rtsp.parser

import android.annotation.SuppressLint
import android.util.Log
import androidx.media3.common.util.ParsableBitArray
import androidx.media3.common.util.ParsableByteArray


// https://tools.ietf.org/html/rfc3640
//          +---------+-----------+-----------+---------------+
//         | RTP     | AU Header | Auxiliary | Access Unit   |
//         | Header  | Section   | Section   | Data Section  |
//         +---------+-----------+-----------+---------------+
//
//                   <----------RTP Packet Payload----------->
@SuppressLint("UnsafeOptInUsageError")
class AacParser(aacMode: String) {
    private val headerScratchBits: ParsableBitArray = ParsableBitArray()
    private val headerScratchBytes: ParsableByteArray = ParsableByteArray()

    private val _aacMode: Int = if (aacMode.equals("AAC-lbr", ignoreCase = true)) MODE_LBR else MODE_HBR
    private val completeFrameIndicator = true

    fun processRtpPacketAndGetSample(data: ByteArray, length: Int): ByteArray? {
        if (DEBUG) Log.v(
            TAG,
            "processRtpPacketAndGetSample(length=$length)"
        )
        var auHeadersCount = 1
        val numBitsAuSize = NUM_BITS_AU_SIZES[_aacMode]
        val numBitsAuIndex = NUM_BITS_AU_INDEX[_aacMode]

        val packet = ParsableByteArray(data, length)

        //      +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+- .. -+-+-+-+-+-+-+-+-+-+
//      |AU-headers-length|AU-header|AU-header|      |AU-header|padding|
//      |                 |   (1)   |   (2)   |      |   (n)   | bits  |
//      +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+- .. -+-+-+-+-+-+-+-+-+-+
        val auHeadersLength =
            packet.readShort().toInt() //((data[0] & 0xFF) << 8) | (data[1] & 0xFF);
        val auHeadersLengthBytes = (auHeadersLength + 7) / 8

        headerScratchBytes.reset(auHeadersLengthBytes)
        packet.readBytes(headerScratchBytes.data, 0, auHeadersLengthBytes)
        headerScratchBits.reset(headerScratchBytes.data)

        val bitsAvailable = auHeadersLength - (numBitsAuSize + numBitsAuIndex)

        if (bitsAvailable > 0) { // && (numBitsAuSize + numBitsAuSize) > 0) {
            auHeadersCount += bitsAvailable / (numBitsAuSize + numBitsAuIndex)
        }

        if (auHeadersCount == 1) {
            val auSize = headerScratchBits.readBits(numBitsAuSize)
            val auIndex = headerScratchBits.readBits(numBitsAuIndex)

            if (completeFrameIndicator) {
                if (auIndex == 0) {
                    if (packet.bytesLeft() == auSize) {
                        return handleSingleAacFrame(packet)
                    } else {
//                        handleFragmentationAacFrame(packet, auSize);
                    }
                }
            } else {
//                handleFragmentationAacFrame(packet, auSize);
            }
        } else {
            if (completeFrameIndicator) {
//                handleMultipleAacFrames(packet, auHeadersLength);
            }
        }
        //        byte[] auHeader = new byte[length-2-auHeadersLengthBytes];
//        System.arraycopy(data,2-auHeadersLengthBytes, auHeader,0, auHeader.length);
//        if (DEBUG)
//            Log.d(TAG, "AU headers size: " + auHeadersLengthBytes + ", AU headers: " + auHeadersCount + ", sample length: " + auHeader.length);
//        return auHeader;
        return ByteArray(0)
    }

    private fun handleSingleAacFrame(packet: ParsableByteArray): ByteArray {
        val length = packet.bytesLeft()
        val data = ByteArray(length)
        System.arraycopy(packet.data, packet.position, data, 0, data.size)
        return data
    }

    companion object {
        private val TAG: String = AacParser::class.java.simpleName
        private const val DEBUG = false

        private const val MODE_LBR = 0
        private const val MODE_HBR = 1

        // Number of bits for AAC AU sizes, indexed by mode (LBR and HBR)
        private val NUM_BITS_AU_SIZES = intArrayOf(6, 13)

        // Number of bits for AAC AU index(-delta), indexed by mode (LBR and HBR)
        private val NUM_BITS_AU_INDEX = intArrayOf(2, 3)

        // Frame Sizes for AAC AU fragments, indexed by mode (LBR and HBR)
        private val FRAME_SIZES = intArrayOf(63, 8191)
    }
}
