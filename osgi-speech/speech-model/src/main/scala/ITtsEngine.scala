package com.cooper.osgi.speech

import scala.concurrent.Future
import com.cooper.osgi.sampled.IAudioReader

/**
 * A structure that can map Strings to ITtsVoices.
 * 	- Also: Translates phrases to an IAudioReader from a given ITtsVoice key.
 * 	- Note: Use ITtsEngine.keys to get available voice keys.
 * @param key The ITtsVoice key to use. (ex: Crystal)
 */
abstract class ITtsEngine(val key: String) extends Ordered[ITtsEngine] {
	/**
	 * Compares two ITtsEngines for ordering.
	 * @param that The ITtsEngine to compare.
	 * @return The integer ordering of the ITtsEngines.
	 */
	def compare(that: ITtsEngine) = this.key compare that.key

	/**
	 * Performs an audio Translation of the given phrase with the given ITtsVoice key.
	 * @param voice The ITtsVoice key to look up.
	 * @param phrase The phrase to translate.
	 * @return The Some(IAudioReader) if successful, else None.
	 */
	def translate(voice: String)(phrase: String): Future[Option[IAudioReader]]
}
