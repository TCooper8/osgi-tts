package com.cooper.osgi.sampled

import java.io.{Closeable, InputStream, OutputStream}
import scala.util.Try

/**
 * An IAudioReader is a monadic class that reads an audio file for streaming.
 * 	- It also allows the sequencing of audio files.
 * @param extensionKey The unique key associated with the IAudioReader
 */
abstract class IAudioReader(val extensionKey: String) extends Closeable with AutoCloseable {
	/**
	 * Binds a stream to this IAudioReader's sequence.
	 * @param inStream The stream to bind.
	 * @return A new IAudioReader with this IAudioReader in sequence.
	 */
	def apply(inStream: InputStream): Try[IAudioReader]

	/**
	 * Binds multiple streams to this IAudioReader's sequence.
	 * @param inStreams The streams to bind in sequence.
	 * @return A new IAudioReader with this IAudioReader in sequence.
	 */
	def apply(inStreams: Iterable[InputStream]): Try[IAudioReader]

	/**
	 * Finalizes the data sequence, closing any streams.
	 */
	def close(): Unit

	/**
	 * Copies the data body to an OutputStream.
	 * @param outStream The stream to copy to.
	 * @return Success(Unit) if no failure occurs, else Failure(error).
	 */
	def copyBodyTo(outStream: OutputStream): Try[Unit]

	/**
	 * Copies the format body to an OutputStream.
	 * @param outStream The stream to copy to.
	 * @return Success(Unit) if no failure occurs, else Failure(error).
	 */
	def copyFormatTo(outStream: OutputStream): Try[Unit]

	/**
	 * Copies all available data to an OutputStream.
	 * @param outStream The stream to copy to.
	 * @return Success(Unit) if no failure occurs, else Failure(error).
	 */
	def copyTo(outStream: OutputStream): Try[Unit]

	/**
	 * Gets the total audio data length in bytes.
	 * @return The bytes in the audio sequence.
	 */
	def getContentLength(): Int
}
