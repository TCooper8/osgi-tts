package com.cooper.osgi.config

trait IConfigurable {
	/**
	 * The node to watch for changes.
	 */
	val configNode: String

	/**
	 * The remote host of the nodes.
	 */
	val configHost: String

	/**
	 * Callback to inform this object that updates have occurred.
	 * @param props The map of updates that have occurred.
	 */
	def configUpdate(props: Iterable[(String, String)]): Unit
}
