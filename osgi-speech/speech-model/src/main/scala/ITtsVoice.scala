package com.cooper.osgi.speech

import com.cooper.osgi.sampled.IAudioReader
import scala.util.Try

/**
 * Represents an interface to a speech synthesizer voice.
 */
trait ITtsVoice {
	// The unique key associated with this voice.
	val key: String

	/**
	 * Attempts to synthesize a given phrase.
	 * @param phrase The phrase to synthesize.
	 * @return A Try[IAudioReader] that contains the synthesized data.
	 */
	def apply(phrase: String): Try[IAudioReader]
}
