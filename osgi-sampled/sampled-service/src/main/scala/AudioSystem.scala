package com.cooper.osgi.sampled.service

import com.cooper.osgi.sampled.{IAudioReader, IAudioSystem}
import scala.collection.mutable

class AudioSystem() extends IAudioSystem {
	private[this] val readers = new mutable.HashMap[String, IAudioReader]()
	  with mutable.SynchronizedMap[String, IAudioReader]

	// Load default readers.
	this put WavReader

	// Override map methods.

	/**
	 * Adds a new key/value pair to this IAudioSystem.
	 * If the IAudioSystem already contains a mapping for the key, it will be overridden by the new value.
	 * @param kv The key/value pair.
	 * @return The IAudioSystem itself.
	 */
	def += (kv: (String, IAudioReader)) = {
		val _ = readers + kv
		this
	}

	/**
	 * Removes a key from this IAudioSystem.
	 * @param key The key associated value to be removed.
	 * @return The IAudioSystem itself.
	 */
	def -= (key: String) = {
		val _ = readers remove key
		this
	}

	/**
	 * Optionally returns the value associated with a key.
	 * @param key The key associated value to retrieve.
	 * @return An Option key associated value.
	 */
	def get(key: String): Option[IAudioReader] =
		readers get key

	/**
	 * Creates a new iterator over all key/value pairs of this IAudioSystem.
	 * @return The new iterator.
	 */
	def iterator: Iterator[(String, IAudioReader)] =
		readers iterator

	/**
	 * Puts an IAudioReader into this IAudioSystem.
	 * @param reader The IAudioReader to insert.
	 * @return The Option value replaced by this insert.
	 */
	def put (reader: IAudioReader) =
		readers put (reader.extensionKey, reader)
}
