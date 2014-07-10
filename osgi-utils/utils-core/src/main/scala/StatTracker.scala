package com.cooper.osgi.utils

import scala.collection.mutable

// TODO: Implement Karaf commands for these utilities.

/**
 * This object is used as a hub for tracking statistics.
 */
object StatTracker {

	/**
	 * This map is used as a central reference.
	 */
	private[this] val map = SafeMap[String, mutable.Map[String, Long]]()
	// TODO: The exact implementation has not been decided, the value 'map' is a placeholder for functionality.

	/**
	 * Simple internal utility for evaluating expressions and throwing away the result.
	 * @param expr The expression to evaluate.
	 */
	private[this] def ignore[A](expr: => A) { expr }

	/**
	 * Utility for providing a proper thread-safe map.
	 * @return Returns a safe mutable map.
	 */
	private[this] def SafeMap[A, B](): mutable.Map[A, B] =
		new mutable.HashMap[A, B]()
			with mutable.SynchronizedMap[A, B]

	/**
	 * Attempts to retrieve a function that can safely store values for tracking.
	 * 	- Warning: This function is only safe because it references a static object type.
	 *
	 * @param name The name associated string to the map that should be retrieved.
	 * @return Returns a function that can safely track String -> Long pairs.
	 */
	def apply(name: String): (String, Long) => Unit = {
		map get name match {
			case Some(m) =>
				(k: String, v: Long) => ignore{ m put (k, v) }

			case None =>
				val m = SafeMap[String, Long]()
				val _ = map put (name, m)
				(k: String, v: Long) => ignore{ m put (k, v) }
		}
	}
}
