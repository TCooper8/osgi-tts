package com.cooper.osgi.config

import scala.concurrent.duration.FiniteDuration

/**
 * This is an interface for a service that spawns IConfigWatcher(s).
 */
trait IConfigService {
	/**
	 * Attempts to create a new IConfigWatcher.
	 * @param config The configurable object.
	 * @param defaultData Some default data to use if none exists.
	 * @param tickTime The configuration refresh time.
	 * @return Returns Some(watcher) or None in the event of failure.
	 */
	def apply(
		config: IConfigurable,
		defaultData: Iterable[(String, String)],
		tickTime: FiniteDuration
			): Option[IConfigWatcher]
}
