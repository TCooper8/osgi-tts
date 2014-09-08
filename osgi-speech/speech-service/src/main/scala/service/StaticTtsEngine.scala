package com.cooper.osgi.speech.service

import com.cooper.osgi.sampled.{IAudioReader, IAudioSystem}
import com.cooper.osgi.io.ILocalFileSystem
import com.cooper.osgi.config.{IConfigurable, IConfigService}
import com.cooper.osgi.speech.ITtsStaticEngine
import scala.concurrent.{ExecutionContext, Future}
import scala.collection.mutable
import ExecutionContext.Implicits.global
import scala.util.{Failure, Success, Try}

case class StaticTtsEngineState(
	var fileSystemType: String,
	var s3SecretKey: String,
	var s3AccessKey: String,
	var s3EndPoint: String
)

/**
 * This class acts as a handler for multiple TTS voices.
 *
 * This class support an IConfiguration of: example:
 * 	staticEngine {
 * 		fileSystemType: String
 * 		s3AccessKey: String
 * 		s3SecretKey: String
 *
 * 		voiceConfig { ... }
 * 		voiceConfig { ... }
 * 	}
 *
 **/
class StaticTtsEngine(
		audioSystem: IAudioSystem,
		localFileSystem: ILocalFileSystem,
		configService: IConfigService,
		val configHost: String,
		val configNode: String
	) extends
		ITtsStaticEngine with
		IConfigurable {

	private[this] val log =
		Utils.getLogger(this)

	private[this] val track =
		Utils.getTracker(Constants.trackerKey)

	/**
	 * Configuration keys
	 */

	private[this] val kFileSystemType = "fileSystemType"
	private[this] val kS3AccessKey = "s3AccessKey"
	private[this] val kS3SecretKey = "s3SecretKey"
	private[this] val kS3EndPoint = "s3EndPoint"

	private[this] val cS3FileSystemType = "s3"
	private[this] val cLocalFileSystemType = "local"

	private[this] val state = StaticTtsEngineState(
		"local", "", "", ""
	)

	private[this] val stagedChanges = mutable.Map[String, String]()

	private[this] val applyStaged = Map(
		kFileSystemType -> {
			v: String => state.fileSystemType = v
		},
		kS3AccessKey -> {
			v: String => state.s3AccessKey = v
		},
		kS3SecretKey -> {
			v: String => state.s3SecretKey = v
		},
		kS3EndPoint -> {
			v: String => state.s3EndPoint = v
		}
	)

	private[this] val propHandleMap = Map(
		kFileSystemType -> {
			v: String => stagedChanges.update(kFileSystemType, v)
		},
		kS3SecretKey -> {
			v: String => stagedChanges.update(kS3SecretKey, v)
		},
		kS3AccessKey -> {
			v: String => stagedChanges.update(kS3AccessKey, v)
		},
		kS3EndPoint -> {
			v: String => stagedChanges.update(kS3EndPoint, v)
		}
	)

	private[this] val voiceMap =
		new mutable.HashMap[String, StaticTtsVoice]()
		  with mutable.SynchronizedMap[String, StaticTtsVoice]

	private[this] val watcher = configService(
		this,
		Nil
	).toOption
	if (watcher.isEmpty)
		log.error(s"$this watcher is undefined, problem with service IConfigService.")

	private[this] def fileSystem = {
		if (state.fileSystemType == cLocalFileSystemType)
			localFileSystem
		else if (state.fileSystemType == cS3FileSystemType) {
			localFileSystem.getS3(
				state.s3AccessKey,
				state.s3SecretKey,
				state.s3EndPoint
			) match {
				case Success(res) => res
				case Failure(err) =>
					log.error(s"Unable to pull S3 file system.", err)
					localFileSystem
			}
		}
		else {
			log.error(s"Bad configuration value for fileSystemType -> ${state.fileSystemType}")
			localFileSystem
		}
	}

	/**
	 * Attempts to spawn a new StaticTtsVoice.
	 * @param key The key used by this voice.
	 * @return A Try[StaticTtsVoice].
	 */
	private[this] def spawnVoice(key: String) {
		Try {
			StaticTtsVoice(
				configService,
				audioSystem,
				fileSystem,
				key,
				this.configHost,
				this.configNode + s"/$key"
			)
		} match {
			case Success(voice) =>
				val oldVoice = voiceMap.put(key, voice)
				oldVoice foreach {
					_.dispose()
				}

			case Failure(err) =>
				log.error(s"Unable to create new voice $key", err)
		}
	}

	private[this] def sync[A](m: A) = state.synchronized(m)

	private[this] def updateKeyVal(k: String, v: String) = {
		propHandleMap get k match {
			case Some(f) => f(v); None
			case None =>
				// If this is the case, the key should be a voice configuration.
				Some(k)
		}
	}

	/**
	 * Callback to inform this object that updates have occurred.
	 * @param props The map of updates that have occurred.
	 */
	def configUpdate(props: Iterable[(String, String)]) {
		log.info(s"Updating with $props")

		sync {
			val voices = props flatMap {
				case (k, v) => updateKeyVal(k, v)
			}

			// TODO: Cut this step out if possible.
			// Staged changes might have been pushed.
			if (stagedChanges.size > 0) {
				stagedChanges foreach {
					case (k, v) => applyStaged get k foreach {
						_(v)
					}
				}
			}

			val diffKeys = voiceMap.keySet -- voices.toSet[String]

			voices foreach {
				key => spawnVoice(key)
			}

			// Regenerate the old voices with the new configuration.
			diffKeys foreach {
				k => spawnVoice(k) // Spawn voice will dispose of replaced voices.
			}

			stagedChanges.clear()
		}
	}

	/**
	 * Performs an audio Translation of the given phrase with the given ITtsVoice key.
	 * @param voice The ITtsVoice key to look up.
	 * @param phrase The phrase to translate.
	 * @return The Future[Try[IAudioReader] ].
	 */
	def translate(voice: String)(phrase: String): Future[Try[IAudioReader]] = Future {
		track.put("Speech:TranslationRequest", 1)

		Try {
			if (!voiceMap.contains(voice))
				track.put(s"BadVoiceRequest:$voice", 1)

			val m = voiceMap get voice map (v => v(phrase))

			assert(m.isDefined, "Unable to translate request.")
			m.get
		}.flatten
	}

	def dispose() {
		watcher foreach {
			_.dispose()
		}
		voiceMap foreach {
			_._2.dispose()
		}
	}
}
