package com.cooper.osgi.tracking.service

import com.cooper.osgi.tracking.{ITrackerService, ITracker}
import scala.collection.mutable


private[this] case class MapTracker() extends ITracker[String] {

	private[this] val map =
		new mutable.HashMap[String, Long]()

	private[this] def sync[A](expr: => A) = this.synchronized(expr)

	/**
	 * Queues the data pair for storage.
	 * @param k Generic key type.
	 * @param v Accumulation count.
	 */
	override def put(k: String, v: Long): Unit =
		sync{ map.put(k, map.getOrElse(k, 0l) + v) }

	/**
	 * Attempts to retrieve the associated count from the given key.
	 * @param key They key associated with the desired result.
	 * @return Returns Some[Long](n) if key was found, else None.
	 */
	override def get(key: String): Option[Long] =
		sync{ map get key }

	/**
	 * Returns an Iterable structure that represents the current state of the data.
	 * @return Iterable key -> value pairs.
	 */
	override def iterable: Iterable[(String, Long)] =
		sync{ map.toIterable }
}

/**
 * This is the main class for the TrackerService.
 * This class extends the ITrackerService with Actors.
 * This class acts as a factory for Actors typed with ITracker[String] interfaces.
 * <p>
 * All methods in this class are using thread-safe structures for insertion and retrieval.
 * <p>
 * The getTracker -> String -> ITracker[String] method promises to either create
 * or retrieve an existing ITracker if previously retrieved under the same name.
 * Trackers retrieved from this object are implemented using Akka TypedActors.
 * <p>
 * Disposing of this object will kill all known actors in the acting ActorSystem,
 * as well as clearing the entire map of stored trackers.
 */
class TrackerService() extends ITrackerService {

	private[this] val trackerMap =
		new mutable.HashMap[String, ITracker[String]]()
			with mutable.SynchronizedMap[String, ITracker[String]]

	private[this] def makeTracker(name: String): ITracker[String] = {
		val tracker = MapTracker()
		trackerMap.update(name, tracker)
		tracker
	}

	/**
	 * Retrieves or creates a String -> Long data tracker.
	 * @param name The name associated with the desired tracker.
	 * @return Returns an existing, or new data tracker.
	 */
	def getTracker(name: String): ITracker[String] =
		trackerMap get name match {
			case Some(out) => out
			case None => makeTracker(name)
		}

	/**
	 * Disposes of this objects allocated resources.
	 */
	def dispose() {
	}
}
