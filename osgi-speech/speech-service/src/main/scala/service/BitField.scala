package com.cooper.osgi.speech.service

class BitField(bits: Int) {
	private[this] val kBitCount = 64
	private[this] val length = bits / kBitCount + 1

	private[this] val cells = Array.fill(length)(0l)

	private[this] def getPos(m: Int) = {
		val k = math.abs(m)
		val i = k / kBitCount % length
		val j = k % kBitCount
		(i, j)
	}

	def clear() {
		List.range(0, length).foreach {
			i => cells.update(i, 0l)
		}
	}

	def put(n: Int) = {
		getPos(n) match {
			case (i, j) =>
				val cell = cells(i)
				val mask = 1l << j
				cells.update(i, cell | mask)
				cell
		}
	}

	def get(n: Int) = {
		getPos(n) match {
			case (i, j) => cells(i) & (1l << j) match {
				case 0 => 0
				case n => 1
			}
		}
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

	override def put(n: Int): Long =
		this.synchronized{ super.put(n) }

	override def get(n: Int): Int =
		this.synchronized{ super.get(n) }

	override def toString(): String =
		this.synchronized{ super.toString() }
}
