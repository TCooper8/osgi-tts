package com.cooper.osgi.sampled

import scala.collection.mutable

/**
 * A structure that maps a String to an IAudioReader.
 */
trait IAudioSystem extends mutable.Map[String, IAudioReader] {
	/**
	 * Puts an IAudioReader into the map using it's IAudioReader.key.
	 * @param reader The IAudioReader to insert.
	 * @return The Option value replaced by this insert.
	 */
	def put(reader: IAudioReader): Option[IAudioReader]
}
