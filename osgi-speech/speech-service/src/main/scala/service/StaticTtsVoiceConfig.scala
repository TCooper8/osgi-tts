package com.cooper.osgi.speech.service

import com.cooper.osgi.config.{IConfigurable, IConfigService}
import com.cooper.osgi.sampled.{IAudioReader, IAudioSystem}
import com.cooper.osgi.io.IFileSystem
import com.cooper.osgi.speech.ITtsVoice
import scala.collection.mutable
import java.util.concurrent.atomic.AtomicReference
import scala.util.{Failure, Success, Try}

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

	private[this] val log =
		Utils.getLogger(this)

	private[this] val track =
		Utils.getTracker(Constants.trackerKey)

	private[this] def maybe[A](expr: => A): Option[A] = maybe("")(expr)
	private[this] def maybe[A](msg: String = "")(expr: => A): Option[A] = {
		Try{ expr } match {
			case Success(m) => Option(m)
			case Failure(err) =>
				log.error(msg, err)
				track.put(err.getClass.getName, 1l)
				None
		}
	}

	val configNode: String = s"$parentNode/$key"

	private[this] val props = SafeMap[String, String](
		"rootPath" -> (System.getProperty("user.dir") + s"/voice/$key"),  //s"/voices/$key",
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
	def apply(phrase: String): Try[IAudioReader] = Try {
		voice.get.map{ _.apply(phrase) }.get
	}.flatten

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
	}

	private[this] def SafeMap[A, B](seq: (A, B)*): mutable.Map[A, B] = {
		new mutable.HashMap[A, B] with mutable.SynchronizedMap[A, B] ++= seq
	}
}
