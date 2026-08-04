package org.koitharu.kotatsu.parsers.site.all

import java.nio.charset.StandardCharsets

/**
 * A minimal reader for the protobuf wire format, enough to walk the responses
 * MANGA Plus returns. The api used to accept `format=json`, but that mode now
 * answers 403 and protobuf is the only encoding left.
 *
 * Only the four wire types the api actually emits are handled; anything else is
 * skipped, so unknown or newly added fields cost nothing.
 */
internal class ProtoMessage private constructor(
	private val fields: Map<Int, List<Any>>,
) {

	fun rawList(field: Int): List<Any> = fields[field].orEmpty()

	fun messages(field: Int): List<ProtoMessage> = rawList(field).mapNotNull { value ->
		(value as? ByteArray)?.let { parse(it) }
	}

	fun message(field: Int): ProtoMessage? = messages(field).firstOrNull()

	fun string(field: Int): String? = (rawList(field).lastOrNull() as? ByteArray)
		?.toString(StandardCharsets.UTF_8)

	fun long(field: Int): Long? = rawList(field).lastOrNull() as? Long

	fun int(field: Int): Int? = long(field)?.toInt()

	companion object {

		fun parse(bytes: ByteArray): ProtoMessage {
			val fields = HashMap<Int, MutableList<Any>>()
			var pos = 0

			fun varint(): Long {
				var result = 0L
				var shift = 0
				while (pos < bytes.size && shift < 64) {
					val b = bytes[pos++].toInt()
					result = result or ((b and 0x7F).toLong() shl shift)
					if (b and 0x80 == 0) return result
					shift += 7
				}
				return result
			}

			while (pos < bytes.size) {
				val tag = varint()
				if (tag == 0L) break
				val field = (tag ushr 3).toInt()
				when ((tag and 0x7L).toInt()) {
					0 -> fields.getOrPut(field) { ArrayList() }.add(varint())

					1 -> {
						if (pos + 8 > bytes.size) return ProtoMessage(fields)
						pos += 8
					}

					2 -> {
						val length = varint().toInt()
						if (length < 0 || pos + length > bytes.size) return ProtoMessage(fields)
						fields.getOrPut(field) { ArrayList() }
							.add(bytes.copyOfRange(pos, pos + length))
						pos += length
					}

					5 -> {
						if (pos + 4 > bytes.size) return ProtoMessage(fields)
						pos += 4
					}

					// Groups and anything unrecognised cannot be skipped safely.
					else -> return ProtoMessage(fields)
				}
			}
			return ProtoMessage(fields)
		}
	}
}
