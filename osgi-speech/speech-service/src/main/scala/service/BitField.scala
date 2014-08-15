package com.cooper.osgi.speech.service

/**
 * Represents a bit field of information.
 * @param bits The number of bits to use.
 */
class BitField(bits: Int) {
	/**
	 * The bit length of a single Long value.
	 */
	private[this] val kBitCount = 64

	/**
	 * The number of Long values needed for use.
	 */
	private[this] val length = bits / kBitCount + 1

	/**
	 * Array of long values.
	 */
	private[this] val cells = Array.fill(length)(0l)

	/**
	 * Maps the given integer to a 2D coordinate in the Array[Long] space.
	 * @param m The bit position.
	 * @return The Array[Long] coordinate of (long index, bit index)
	 */
	private[this] def getPos(m: Int) = {
		val k = math.abs(m)
		val i = k / kBitCount % length
		val j = k % kBitCount
		(i, j)
	}

	/**
	 * Resets all values to 0 in the bit field.
	 */
	def clear() {
		List.range(0, length).foreach {
			i => cells.update(i, 0l)
		}
	}

	/**
	 * Flips the bit at position n to 1.
	 * @param n The bit position to flip to 1.
	 * @return Returns the previous state of the bit.
	 */
	def put(n: Int): Int = {
		getPos(n) match {
			case (i, j) =>
				val cell = cells(i)
				val mask = 1l << j
				cells.update(i, cell | mask)

				if ((cell & mask) != 0l) 1
				else 0
		}
	}

	/**
	 * Returns the bit flag at the given index of n.
	 * @param n The index of the bit flag.
	 * @return Returns the state of the bit.
	 */
	def get(n: Int) = {
		getPos(n) match {
			case (i, j) => cells(i) & (1l << j) match {
				case 0 => None
				case n => Some(1)
			}
		}
	}

	def iterator: Iterator[(Int, Int)] =
		Seq.range(0, bits).iterator.flatMap {
			i => get(i).map{ v => (i, v) }
		}

	private[this] def longToBitString(n: Long) = {
		def iter(acc: String, i: Int): String = {
			i < kBitCount match {
				case false => acc
				case true =>
					n & (1l << i) match {
						case 0 => iter(acc + "0", i + 1)
						case n => iter(acc + "1", i + 1)
					}
			}
		}
		iter("", 0)
	}

	override def toString() = {
		cells.foldLeft("") {
			case (acc, n) =>
				acc + longToBitString(n)
		}
	}.substring(0, bits)
}

class SynchronizedBitField(bits: Int) extends BitField(bits) {
	override def clear(): Unit =
		this.synchronized{ super.clear() }

	override def put(n: Int): Int =
		this.synchronized{ super.put(n) }

	override def get(n: Int): Option[Int] =
		this.synchronized{ super.get(n) }

	override def toString(): String =
		this.synchronized{ super.toString() }
}
