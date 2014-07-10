package com.cooper.osgi.config

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
	 * Pushes a String -> Array[Byte] to the configuration.
	 * @param key The key to push the data to.
	 * @param data The actual data to push.
	 */
	def putData(key: String, data: String): Unit
}
