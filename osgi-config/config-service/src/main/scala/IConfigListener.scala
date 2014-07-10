package com.cooper.osgi.config.service

trait IConfigListener {
	/**
	 * Alerts this object to shutdown.
	 * @param rc The return code.
	 */
	def closing(rc: Int): Unit

	/**
	 * Callback for an exists check.
	 * @param data The data to compare.
	 */
	def exists(data: Array[Byte]): Unit
}
