package com.cooper.osgi.speech

import com.cooper.osgi.sampled.IAudioReader

/**
 * Represents an interface to a speech synthesizer voice.
 */
trait ITtsVoice {
	// The unique key associated with this voice.
	val key: String

	/**
	 * Attempts to synthesize a given phrase.
	 * @param phrase The phrase to synthesize.
	 * @return An Option IAudioReader that contains the synthesized data.
	 */
	def apply(phrase: String): Option[IAudioReader]
}
