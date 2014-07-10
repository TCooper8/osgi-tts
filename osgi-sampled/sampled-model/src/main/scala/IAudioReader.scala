package com.cooper.osgi.sampled

import java.io.{Closeable, InputStream, OutputStream}

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
	def apply(inStream: InputStream): Option[IAudioReader]

	/**
	 * Binds multiple streams to this IAudioReader's sequence.
	 * @param inStreams The streams to bind in sequence.
	 * @return A new IAudioReader with this IAudioReader in sequence.
	 */
	def apply(inStreams: Iterable[InputStream]): Option[IAudioReader]

	/**
	 * Finalizes the data sequence, closing any streams.
	 */
	def close(): Unit

	/**
	 * Copies the data body to an OutputStream.
	 * @param outStream The stream to copy to.
	 */
	def copyBodyTo(outStream: OutputStream): Unit

	/**
	 * Copies the format body to an OutputStream.
	 * @param outStream The stream to copy to.
	 */
	def copyFormatTo(outStream: OutputStream): Unit

	/**
	 * Copies all available data to an OutputStream.
	 * @param outStream The stream to copy to.
	 */
	def copyTo(outStream: OutputStream): Unit

	/**
	 * Gets the total audio data length in bytes.
	 * @return The bytes in the audio sequence.
	 */
	def getContentLength(): Int
}
