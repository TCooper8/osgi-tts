package com.cooper.osgi.sampled.service

import java.nio.{ByteOrder, ByteBuffer}
import com.cooper.osgi.sampled.IAudioReader
import java.io.{OutputStream, InputStream}
import scala.util.{Failure, Success, Try}

/**
 * A WavReader is a class that parses a WAVE file and allows for copy to an OutputStream.
 * 	- The sum of the header information is collected on class construction.
 * @param inStream The stream to the WAVE data.
 * @param parent The parent WavReader is the next WAVE node in sequence to read data from.
 */
class WavReader(inStream: InputStream, parent: IWavReader) extends IWavReader {
	// Header information for the WAVE file chunk.
	val chunkID: String = getString(4)
	val chunkSize: Int = getLEInt() + parent.chunkSize
	val format: String = getString(4)

	// Header information for the WAVE file format chunk.
	val fmtChunkID: String = getString(4)
	val fmtChunkSize: Int = getLEInt()
	val audioFormat: Short = getLEShort()
	val numChannels: Short = getLEShort()
	val sampleRate: Int = getLEInt()
	val byteRate: Int = getLEInt()
	val blockAlign: Short = getLEShort()
	val bitsPerSample: Short = getLEShort()

	// Header information for the WAVE file data chunk.
	val dataChunkID: String = getString(4)
	val dataChunkSize: Int = getLEInt() + parent.dataChunkSize

	// Assertions to validate the WAVE file.

	assert(compareFormat(parent), "WavReader parent format differs from child.")
	assert(byteRate == sampleRate * blockAlign)
	assert(inStream.available() + parent.dataChunkSize == dataChunkSize)

	/**
	 * Binds a stream to this IAudioReader's sequence.
	 * @param inStream The stream to bind.
	 * @return A new IAudioReader with this IAudioReader in sequence.
	 */
	def apply(inStream: InputStream): Try[IAudioReader] = Try {
		new WavReader(inStream, this)
	}

	/**
	 * Binds multiple streams to this IAudioReader's sequence.
	 * @param inStreams The streams to bind in sequence.
	 * @return A new IAudioReader with this IAudioReader in sequence.
	 */
	def apply(inStreams: Iterable[InputStream]): Try[IAudioReader] = Try{
		def part(acc: Try[IAudioReader], s: InputStream) = {
			acc.flatMap{ _.apply(s) }
		}
		inStreams.foldLeft(Try{this.asInstanceOf[IAudioReader]})(part)
	}.flatten

	/**
	 * Finalizes the data sequence, closing any streams.
	 */
	def close() {
		inStream.close()
		parent.close()
	}

	/**
	 * Copies the data body to an OutputStream.
	 * @param outStream The stream to copy to.
	 */
	def copyBodyTo(outStream: OutputStream) = Try {
		Try { Utils.copy(inStream, outStream) }.map {
			_ => parent.copyBodyTo(outStream)
		}
	}

	/**
	 * Copies the format body to an OutputStream.
	 * @param outStream The stream to copy to.
	 */
	def copyFormatTo(outStream: OutputStream) = Try {
		val buffer = getLEByteBuffer(44)
		buffer.put(chunkID.getBytes())
		buffer.putInt(fmtChunkSize)
		buffer.put(format.getBytes())
		buffer.put(fmtChunkID.getBytes())
		buffer.putInt(fmtChunkSize)
		buffer.putShort(audioFormat)
		buffer.putShort(this.numChannels)
		buffer.putInt(this.sampleRate)
		buffer.putInt(this.byteRate)
		buffer.putShort(blockAlign)
		buffer.putShort(bitsPerSample)
		buffer.put(dataChunkID.getBytes())
		buffer.putInt(dataChunkSize)

		Try { outStream.write(buffer.array()) }
	}.flatten

	/**
	 * Copies all available data to an OutputStream.
	 * @param outStream The stream to copy to.
	 */
	def copyTo(outStream: OutputStream) = Try {
		copyFormatTo(outStream).map {
			_ => copyBodyTo(outStream)
		}
	}

	/**
	 * Gets the total audio data length in bytes.
	 * @return The bytes in the audio sequence.
	 */
	def getContentLength(): Int =
		dataChunkSize + 44

	//
	//	The following are helper functions for validating WAVE file data, and for utilities.
	//

	private[this] def getChunk(n: Int) = {
		val buf = new Array[Byte](n)
		val nBytes = inStream.read(buf)
		assert(nBytes == n)
		buf
	}

	private[this] def getString(n: Int) =
		new String(getChunk(n))

	private[this] def getLEInt() =
		ByteBuffer.wrap(getChunk(4)).order(ByteOrder.LITTLE_ENDIAN).getInt()

	private[this] def getBEInt() =
		ByteBuffer.wrap(getChunk(4)).getInt()

	private[this] def getLEShort() =
		ByteBuffer.wrap(getChunk(2)).order(ByteOrder.LITTLE_ENDIAN).getShort()

	private[this] def getLEByteBuffer(nBytes: Int) = {
		val buffer = ByteBuffer.allocate(nBytes)
		buffer.order(ByteOrder.LITTLE_ENDIAN)
		buffer
	}
}

/**
 * This is the zero state of the WavReader monad.
 */
object WavReader extends IWavReader {
	val chunkID: String = "RIFF"
	val chunkSize: Int = 0
	val format: String = "WAVE"
	val fmtChunkID: String = "fmt "
	val fmtChunkSize: Int = 0x10
	val audioFormat: Short = 1
	val numChannels: Short = 0
	val sampleRate: Int = 0
	val byteRate: Int = 0
	val blockAlign: Short = 0
	val bitsPerSample: Short = 0
	val dataChunkID: String = "data"
	val dataChunkSize: Int = 0

	def apply(inStream: InputStream): Try[IAudioReader] = Try {
		new WavReader(inStream, this)
	}

	def apply(inStreams: Iterable[InputStream]): Try[IAudioReader] = Try {
		inStreams match {
			case Nil => Success(this)
			case s :: ss => apply(s).flatMap{ w => w(ss) }
		}
	}.flatten

	def close(): Unit = ()
	def copyBodyTo(outStream: OutputStream): Try[Unit] = Success(Unit)
	def copyFormatTo(outStream: OutputStream): Try[Unit] = Success(Unit)
	def copyTo(outStream: OutputStream): Try[Unit] = Success(Unit)

	/**
	 * Gets the total audio data length in bytes.
	 * @return The bytes in the audio sequence.
	 */
	def getContentLength(): Int = 0
}

