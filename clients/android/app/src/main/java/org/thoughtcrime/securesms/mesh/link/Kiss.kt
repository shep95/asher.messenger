/*
 * Asher Messenger: offline mesh transport (meshlink).
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.mesh.link

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * KISS framing and the RNode command set, a port of `meshlink/src/kiss.rs`. The Rust node only
 * exchanges raw meshlink frames, so serial radios are framed here: each meshlink frame becomes
 * one KISS data frame (`CMD_DATA`), and every data frame received becomes one `MeshNode_LinkWrite`.
 */
object Kiss {
  const val FEND: Int = 0xC0
  const val FESC: Int = 0xDB
  const val TFEND: Int = 0xDC
  const val TFESC: Int = 0xDD

  /** Standard KISS data command. */
  const val CMD_DATA: Int = 0x00

  /** RNode firmware commands; check against the firmware you flash. */
  const val CMD_FREQUENCY: Int = 0x01
  const val CMD_BANDWIDTH: Int = 0x02
  const val CMD_TXPOWER: Int = 0x03
  const val CMD_SPREADING_FACTOR: Int = 0x04
  const val CMD_CODING_RATE: Int = 0x05
  const val CMD_RADIO_STATE: Int = 0x06
  const val CMD_DETECT: Int = 0x08
  const val CMD_READY: Int = 0x0F

  /** `[FEND][command][escaped payload][FEND]`. */
  fun encode(command: Int, payload: ByteArray): ByteArray {
    val out = ByteArrayOutputStream(payload.size + 4)
    out.write(FEND)
    out.write(command)
    for (b in payload) {
      when (b.toInt() and 0xFF) {
        FEND -> {
          out.write(FESC)
          out.write(TFEND)
        }
        FESC -> {
          out.write(FESC)
          out.write(TFESC)
        }
        else -> out.write(b.toInt())
      }
    }
    out.write(FEND)
    return out.toByteArray()
  }

  /** Incremental decoder that copes with frames split across reads and with noise between frames. */
  class Decoder {
    private var inFrame = false
    private var escaped = false
    private var command: Int = -1
    private val buf = ByteArrayOutputStream()

    /** Feeds bytes; returns every complete (command, payload) frame. */
    fun feed(bytes: ByteArray, length: Int = bytes.size): List<Pair<Int, ByteArray>> {
      val frames = ArrayList<Pair<Int, ByteArray>>()
      for (i in 0 until length) {
        val b = bytes[i].toInt() and 0xFF
        if (b == FEND) {
          if (inFrame) {
            if (command >= 0) {
              frames.add(command to buf.toByteArray())
            }
            buf.reset()
            escaped = false
          }
          inFrame = true
          command = -1
          continue
        }
        if (!inFrame) continue
        if (command < 0) {
          command = b
          continue
        }
        if (escaped) {
          escaped = false
          when (b) {
            TFEND -> buf.write(FEND)
            TFESC -> buf.write(FESC)
            else -> {
              // Invalid escape: drop the frame.
              inFrame = false
              command = -1
              buf.reset()
            }
          }
          continue
        }
        if (b == FESC) {
          escaped = true
          continue
        }
        buf.write(b)
      }
      return frames
    }
  }

  /** Radio parameters for an RNode LoRa board (`RadioConfig` in kiss.rs). */
  data class RadioConfig(
    val frequencyHz: Long,
    val bandwidthHz: Long,
    val txPowerDbm: Int,
    val spreadingFactor: Int,
    val codingRate: Int
  ) {
    /** The command frames that configure and enable the radio, in order. */
    fun toFrames(): List<ByteArray> {
      return listOf(
        encode(CMD_FREQUENCY, u32be(frequencyHz)),
        encode(CMD_BANDWIDTH, u32be(bandwidthHz)),
        encode(CMD_TXPOWER, byteArrayOf(txPowerDbm.toByte())),
        encode(CMD_SPREADING_FACTOR, byteArrayOf(spreadingFactor.toByte())),
        encode(CMD_CODING_RATE, byteArrayOf(codingRate.toByte())),
        encode(CMD_RADIO_STATE, byteArrayOf(0x01))
      )
    }

    /** Largest meshlink frame that fits one LoRa transmission on this profile. */
    fun frameMtu(): Int = if (spreadingFactor >= 11) 120 else 200

    private fun u32be(value: Long): ByteArray = ByteBuffer.allocate(4).putInt(value.toInt()).array()

    companion object {
      /** 868.0 MHz, 125 kHz, SF 10, CR 4/5, 14 dBm. The legal band and power are the operator's responsibility. */
      val EU_LONG_RANGE = RadioConfig(frequencyHz = 868_000_000L, bandwidthHz = 125_000L, txPowerDbm = 14, spreadingFactor = 10, codingRate = 5)
    }
  }
}
