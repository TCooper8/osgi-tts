package com.cooper.osgi.speech.service

import com.cooper.osgi.config.{IConfigurable, IConfigService}
import com.cooper.osgi.sampled.{IAudioReader, IAudioSystem}
import com.cooper.osgi.io.IFileSystem
import com.cooper.osgi.speech.ITtsVoice
import scala.util.{Success, Try, Failure}

/**
 * This class is intended to manage the streaming of multiple audio files.
 * 	- For a static translation.
 *
 * This class supports an IConfiguration of: example:
 *
 * name {
 * 		filePrefix = "DIR/TTS_"
 * 		fileSuffix = ".wav"
 * 		rootPath = "voices"
 * }
 *
 */
case class StaticTtsVoice(
		configService: IConfigService,
		audioSystem: IAudioSystem,
		fileSystem: IFileSystem,
		key: String,
		configHost:String,
		configNode:String
	) extends ITtsVoice with IConfigurable {

	private[this] val log =
		Utils.getLogger(this)

	private[this] val track =
		Utils.getTracker(Constants.trackerKey)

	/**
	 * Configuration keys
	 */

	private[this] val kRootPath = "rootPath"
	private[this] val kFileSuffix = "fileSuffix"
	private[this] val kFilePrefix = "filePrefix"

	/**
	 * Tracking keys.
	 */

	val partialTranslationFailure = "partialTranslationFailure"

	/**
	 * Configuration variables.
	 */

	private[this] var rootPath =
		"tts-" + configNode


	private[this] var fileSuffix =
		".wav"

	private[this] var filePrefix =
		""

	private[this] var reader =
		audioSystem.get(fileSuffix)

	private[this] var bucket =
		fileSystem.getBucket(rootPath)

	/**
	 * This maps configuration keys to functionality within this class.
	 */
	private[this] val propHandleMap = Map(
		kFilePrefix -> { v:String =>
			filePrefix = v
		},
		kFileSuffix -> { v:String =>
		  	fileSuffix = v
			reader = audioSystem.get(v)
		},
		kRootPath -> { v:String =>
			rootPath = v
		  	bucket =
			  fileSystem.getBucket(v)
				.orElse (fileSystem.createBucket(v))
		}
	)

	private[this] val watcher = configService.apply(
		this,
		Map(
			kRootPath -> rootPath,
			kFilePrefix -> filePrefix,
			kFileSuffix -> fileSuffix
		)
	)
	watcher match {
		case Failure(err) =>
			// This will ensure that the StaticTtsEngine does not add this to it's voice map.
			throw err
		case _ => ()
	}

	private[this] def sync[A](expr: => A) =
		this.synchronized(expr)

	/**
	 * Callback to inform this object that updates have occurred.
	 * @param props The map of updates that have occurred.
	 */
	def configUpdate(props: Iterable[(String, String)]) {
		log.info(s"Updating with $props")
		sync {
			props.foreach {
				case (k, v) =>
					propHandleMap.get(k).foreach{ _.apply(v) }
			}
		}
	}

	def apply(phrase: String): Try[IAudioReader] = {
		// Safely pull the mutable state data.
		val (reader, prefix, suffix, bucket) = sync {
			(this.reader.get, this.filePrefix, this.fileSuffix, this.bucket)
		}

		// Short circuit if the root bucket is not valid.
		bucket.flatMap { bucket =>
			// Split the phrase into individual words.
			val words = { phrase split ' ' }.reverse.toList

			// Attempt to open each word as a file, and pull the data stream.
			// tryToOption will log the failures, this embraces partial translation failure.
			val streams = words.flatMap { word =>
				val res = tryToOption {
					val key = s"$prefix$word$suffix"
					bucket.read(key).flatMap{ _.content }
				}

				//Sometimes the file cannot be pulled for translation, the proxy needs to know about this.
				if (res.isEmpty) {
					log.warn(s"The file stream of $word could not be pulled for translation.")
					track.put(partialTranslationFailure, 1)
				}
				res
			}

			// Apply the list of streams to the reader.
			reader.apply(streams)
		}
	}

	private[this] def tryToOption[A](expr: Try[A]): Option[A] =
		expr match {
			case Failure(err) =>
				log.error(err.getMessage())
				track.put(err.getClass.getName, 1l)
				None
			case Success(res) => Some(res)
		}

	def dispose() {
		watcher foreach { _.dispose() }
	}
}
