package com.cooper.osgi.speech.service

import com.cooper.osgi.sampled.{IAudioReader, IAudioSystem}
import com.cooper.osgi.io.IFileSystem
import com.cooper.osgi.config.{IConfigurable, IConfigService}
import com.cooper.osgi.speech.ITtsStaticEngine
import scala.concurrent.{ExecutionContext, Future}
import scala.collection.mutable
import ExecutionContext.Implicits.global
import scala.util.Try

class StaticTtsEngine(
		audioSystem: IAudioSystem,
		fileSystem: IFileSystem,
		configService: IConfigService,
		encoding: String,
		val configHost: String,
		val configNode: String
	) extends
		ITtsStaticEngine with
		IConfigurable
	{

	private[this] val log =
		Utils.getLogger(this)

	private[this] val track =
		Utils.getTracker(Constants.trackerKey)

	private[this] val voiceMap =
		new mutable.HashMap[String, StaticTtsVoiceConfig]()
			with mutable.SynchronizedMap[String, StaticTtsVoiceConfig]

	private[this] val watcher = configService(
		this,
		Nil
	).toOption
	if (watcher.isEmpty)
		log.error(s"$this watcher is undefined, problem with service IConfigService.")

	/**
	 * Callback to inform this object that updates have occurred.
	 * @param props The map of updates that have occurred.
	 */
	def configUpdate(props: Iterable[(String, String)]) {
		props foreach {
			case (key, _) =>
				if (!voiceMap.contains(key)) {
					log.info(s"Creating new voice $key")
					val voice = new StaticTtsVoiceConfig(
						configService,
						audioSystem,
						fileSystem,
						this.configNode,
						encoding,
						key,
						this.configHost
					)
					voiceMap put (key, voice)
				}
		}
	}

	/**
	 * Performs an audio Translation of the given phrase with the given ITtsVoice key.
	 * @param voice The ITtsVoice key to look up.
	 * @param phrase The phrase to translate.
	 * @return The Some(IAudioReader) if successful, else None.
	 */
	def translate(voice: String)(phrase: String): Future[Try[IAudioReader]] = Future{
		track.put("Speech:TranslationRequest", 1)

		Try {
			val m = voiceMap get voice map (v => v(phrase))
			m.get
		}.flatten
	}

	def dispose() {
		watcher foreach { _.dispose() }
	}
}
