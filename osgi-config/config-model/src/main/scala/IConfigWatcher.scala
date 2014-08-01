package com.cooper.osgi.config

import java.io.InputStream
import scala.util.Try

/**
 * This is an interface for a remote configuration node.
 */
trait IConfigWatcher {
	/**
	 * The remote host to watch.
	 */
	val host: String

	/**
	 * The remote node to watch.
	 */
	val node: String

	/**
	 * Cleans up any resources allocated to this object.
	 */
	def dispose(): Unit

	/**
	 * Pushes a String -> String to the configuration.
	 * @param key The key to push the data to.
	 * @param data The actual data to push.
	 */
	def putData(key: String, data: String): Unit

	/**
	 * Attempts to parse the contents of an InputStream and push it into configuration.
	 * 	- Parses the InputStream using 'typesafe.config.ConfigFactory.parse'.
	 * @param inStream The stream of data.
	 */
	def putData(inStream: InputStream): Try[Unit]
}
