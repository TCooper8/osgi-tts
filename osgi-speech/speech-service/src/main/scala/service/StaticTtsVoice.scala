package com.cooper.osgi.speech.service

import com.cooper.osgi.speech.ITtsVoice
import com.cooper.osgi.sampled.{IAudioReader, IAudioSystem}
import com.cooper.osgi.io.{IFileSystem, INode}
import com.cooper.osgi.utils.{MaybeLog, StatTracker, Logging}
import scala.util.{Try, Success, Failure}

/**
 * Represents a static speech synthesizer voice.
 * @param audioSystem The IAudioSystem to use for reading audio files.
 * @param fileSystem The file system to use.
 * @param key The unique voice key associated with this voice.
 * @param rootPath The root directory to look for files.
 * @param filePrefix The file prefix to use.
 * @param fileSuffix The file suffix to use, this is also the key associated with the IAudioReader in the given IAudioSystem
 **/
class StaticTtsVoice(
		audioSystem: IAudioSystem,
		fileSystem: IFileSystem,
		val key: String,
		rootPath: String,
		filePrefix: String,
		fileSuffix: String
	) extends ITtsVoice {

	private[this] val log = Logging(key)

	private[this] val track = StatTracker(Constants.trackerKey)

	private[this] val maybe = MaybeLog(log, track)

	private[this] val rootFile = fileSystem.getBucket(rootPath).get

	/**
	 * Pulls an optional IAudioReader object from the audioSystem.
	 * @return The optional IAudioReader associated with the file suffix.
	 */
	private[this] def reader = audioSystem get (fileSuffix)

	/**
	 * Converts a given string to a valid file name in the form of filePrefix + str + fileSuffix..
	 * @param str The string to convert.
	 * @return A converted string to a file name.
	 */
	private[this] def toKey(str: String) =
		s"$filePrefix$str$fileSuffix"

	/**
	 * Converts a given file name (key) to a file.
	 * @param key The file name to open.
	 * @return Some(file) if the file could be opened and read, else None.
	 */
	private[this] def toFile(key: String) =
		rootFile.read(key)

	/**
	 * Opens the stream associated with the given word.
	 * @param word The word to convert to file, and try open stream.
	 * @return Some(stream) if the stream could be opened, else None.
	 */
	private[this] def toStream(word: String) = {
		tryToOption {
			toFile(toKey(word)).flatMap {
				file => file.content
			}
		}
	}

	/**
	 * Statically synthesizes a given phrase.
	 * @param phrase The phrase to synthesize.
	 * @return An Option IAudioReader that contains the synthesized data.
	 */
	def apply(phrase: String): Option[IAudioReader] = maybe {
		val words = (phrase split ' ').toList.reverse
		val streams = words.map(toStream).flatten
		reader flatMap (r => r(streams))
	}.flatten

	private[this] def tryToOption[A](expr: Try[A]): Option[A] =
		expr match {
			case Failure(err) =>
				log.error("", err)
				None
			case Success(res) => Some(res)
		}
}
