package com.cooper.osgi.speech.service

import com.cooper.osgi.config.{IConfigurable, IConfigService}
import com.cooper.osgi.sampled.{IAudioReader, IAudioSystem}
import com.cooper.osgi.io.{IFileSystem, INode}
import com.cooper.osgi.speech.ITtsVoice
import com.cooper.osgi.utils.{MaybeLog, StatTracker, Logging}
import scala.collection.mutable
import scala.concurrent.duration._
import java.util.concurrent.atomic.AtomicReference

/**
 * Represents a static speech synthesizer voice.
 * @param audioSystem The IAudioSystem to use for reading audio files.
 * @param key The unique voice key associated with this voice.
 **/
class StaticTtsVoiceConfig(
		watcherService: IConfigService,
		audioSystem: IAudioSystem,
		fileSystem: IFileSystem,
		parentNode: String,
		encoding: String,
		val key: String,
		val configHost: String
	) extends
		ITtsVoice with
		IConfigurable
	{

	private[this] val log = Logging(key)

	private[this] val track = StatTracker(Constants.trackerKey)

	private[this] val maybe = MaybeLog(log, track)

	val configNode: String = s"$parentNode/$key"

	private[this] val props = SafeMap[String, String](
		"rootPath" -> s"/voices/$key",
		"filePrefix" -> "",
		"fileSuffix" -> ".wav"
	)

	private[this] val voice = new AtomicReference(makeVoice)

	private[this] val watcher = watcherService(
		this,
		props
	).toOption

	if (watcher.isEmpty)
		log.error(s"$this watcher is undefined, problem with ${watcher.getClass}")

	/**
	 * Statically synthesizes a given phrase.
	 * @param phrase The phrase to synthesize.
	 * @return An Option IAudioReader that contains the synthesized data.
	 */
	def apply(phrase: String): Option[IAudioReader] = voice.get flatMap (v => v(phrase))

	/**
	 * Callback to inform this object that updates have occurred.
	 * @param props The map of updates that have occurred.
	 */
	def configUpdate(props: Iterable[(String, String)]) {
		props foreach {
			case (k, v) =>
				this.props put (k, v)
				log.info(s"Updated $k -> $v")
		}

		if (props.nonEmpty)
			voice.set(makeVoice)
	}

	def dispose() {
		watcher foreach { _.dispose() }
	}

	object PropsExtract {
		def unapply(m: mutable.Map[String, String]) = {
			(m get "rootPath", m get "filePrefix", m get "fileSuffix") match {
				case (Some(root), Some(prefix), Some(suffix)) => Some(root, prefix, suffix)
				case _ => None
			}
		}
	}

	/*private[this] val RootPath = PropExtract("rootPath")
	private[this] val FilePrefix = PropExtract("filePrefix")
	private[this] val FileSuffix = PropExtract("fileSuffix")*/

	//private[this] def rootPath = props get "rootPath"
	//private[this] def filePrefix = props get "filePrefix"
	//private[this] def fileSuffix = props get "fileSuffix"

	private[this] def makeVoice: Option[StaticTtsVoice] = {
		props match {
			case PropsExtract(rootPath,filePrefix,fileSuffix) =>
				maybe {
					new StaticTtsVoice(
						audioSystem,
						fileSystem,
						key,
						rootPath,
						filePrefix,
						fileSuffix
					)
				}

			case _ =>
				log.error("Unable to create new voice.")
				None
		}
/*
		(rootPath, filePrefix, fileSuffix) match {
			case (Some(rootPath), Some(filePrefix), Some(fileSuffix)) =>
				maybe {
					new StaticTtsVoice(
						audioSystem,
						fileSystem,
						key,
						rootPath,
						filePrefix,
						fileSuffix
					)
				}
			case _ =>
				log.error("Unable to create new voice")
				None
		}*/
	}

	private[this] def SafeMap[A, B](seq: (A, B)*): mutable.Map[A, B] = {
		new mutable.HashMap[A, B] with mutable.SynchronizedMap[A, B] ++= seq
	}
}
