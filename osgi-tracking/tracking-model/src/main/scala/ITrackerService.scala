package com.cooper.osgi.tracking

/**
 * Represents an interface for a data tracking accumulator.
 * 	- Ex: [ Failure -> 5; Success -> 10 ]
 * @tparam A Generic data type.
 */
trait ITracker[A] {

	/**
	 * Queues the data pair for storage.
	 * @param k Generic key type.
	 * @param v Accumulation count.
	 */
	def put(k: A, v: Long): Unit

	/**
	 * Attempts to retrieve the associated count from the given key.
	 * @param key They key associated with the desired result.
	 * @return Returns Some[Long](n) if key was found, else None.
	 */
	def get(key: A): Option[Long]

	/**
	 * Returns an Iterable structure that represents the current state of the data.
	 * @return Iterable key -> value pairs.
	 */
	def iterable: Iterable[(A, Long)]
}

/**
 * Represents an interface for a data tracking service.
 * This interface is meant to act as a front for a service model.
 */
trait ITrackerService {

	/**
	 * Retrieves or creates a String -> Long data tracker.
	 * @param name The name associated with the desired tracker.
	 * @return Returns an existing, or new data tracker.
	 */
	def getTracker(name: String): ITracker[String]
}
