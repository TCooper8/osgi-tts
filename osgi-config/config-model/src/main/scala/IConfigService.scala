package com.cooper.osgi.config

import scala.util.Try

/**
 * This is an interface for a service that spawns IConfigWatcher(s).
 */
trait IConfigService {
	/**
	 * Attempts to create a new IConfigWatcher.
	 * @param config The configurable object.
	 * @param defaultData Some default data to use if none exists.
	 * @return Returns Success(watcher) or Failure(error) in the event of failure.
	 */
	def apply(
		config: IConfigurable,
		defaultData: Iterable[(String, String)]
			): Try[IConfigWatcher]
}
