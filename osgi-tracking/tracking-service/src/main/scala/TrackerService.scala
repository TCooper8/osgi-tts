package com.cooper.osgi.tracking.service

import akka.actor.{PoisonPill, TypedProps, TypedActor, ActorSystem}
import com.cooper.osgi.tracking.{ITrackerService, ITracker}
import com.typesafe.config.{ConfigFactory, Config}
import scala.collection.mutable

/**
 * This is an implementation of ITracker[A] to act as a
 * TypedActor interface for an Actor. As a result, this class
 * is left non-thread safe.
 * @param name The name of the tracker.
 * @tparam A Generic data type.
 */
case class Tracker[A](name: String) extends ITracker[A] {

	/**
	 * This is a cache that acts as the storage for the data.
	 */
	private[this] val cache: mutable.Map[A, Long] =
		mutable.Map[A, Long]()

	/**
	 * Queues the data pair for storage.
	 * @param k Generic key type.
	 * @param v Accumulation count.
	 */
	def put(k: A, v: Long) {
		cache.update(k, v + cache.getOrElse(k, 0l))
	}

	/**
	 * Attempts to retrieve the associated count from the given key.
	 * @param key They key associated with the desired result.
	 * @return Returns Some[Long](n) if key was found, else None.
	 */
	def get(key: A): Option[Long] =
		cache get key

	/**
	 * Returns an Iterable structure that represents the current state of the data.
	 * @return Iterable key -> value pairs.
	 */
	def iterable: Iterable[(A, Long)] =
		cache.toIterable
}

trait ITrackerServiceState {
	def getTracker(name: String): ITracker[String]
	def dispose(): Unit
}

/**
 * This acts as the mutable state of the TrackerService.
 * Intentionally left non-thread safe.
 */
class TrackerServiceState(context: ActorSystem) extends ITrackerServiceState {

	// This is the map of active trackers.
	private[this] val trackers =
		mutable.Map[String, ITracker[String]]()

	/**
	 * Attempts to retrieve or create a valid ITracker object.
	 * @param name The key associated with the desired ITracker.
	 * @return Returns a thread-safe ITracker.
	 */
	def getTracker(name: String): ITracker[String] = {
		// Attempt to retrieve an existing tracker from memory.

		trackers get name match {
			case Some(tracker) => tracker
			case None =>
				// No tracker is found, create a TypedActor with the ITracker interface.
				val tracker: ITracker[String] = TypedActor(context).typedActorOf(
					TypedProps(
						classOf[ITracker[String]],
						Tracker[String](name)
					)
				)
				// Place the tracker into memory for later retrieval.
				val _ = trackers.put(name, tracker)
				tracker
		}
	}

	/**
	 * Disposes of this objects allocated resources.
	 */
	def dispose() {
		trackers.keys.foreach {
			key => trackers.remove(key)
		}
	}
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

	private[this] val kActorSystemName = this.getClass.getName.replace(".", "")

	/**
	 * This is the active ActorSystem for this bundle.
	 */
	private[this] val actorSystem = getActorSystem

	/**
	 * Attempts to retrieve the ActorSystem.
	 */
	private[this] def getActorSystem = {
		val loader = classOf[ActorSystem].getClassLoader()
		val config: Config = {
			val config = ConfigFactory.defaultReference(loader)
			config.checkValid(ConfigFactory.defaultReference(loader), "akka")
			config
		}
		ActorSystem(kActorSystemName, config, loader)
	}

	/**
	 * This acts as this object's mutable state, wrapped behind a TypedActor.
	 */
	private[this] val state: ITrackerServiceState =
		TypedActor(actorSystem).typedActorOf(
			TypedProps(
				classOf[ITrackerServiceState],
				new TrackerServiceState(actorSystem)
			)
		)

	/**
	 * Retrieves or creates a String -> Long data tracker.
	 * @param name The name associated with the desired tracker.
	 * @return Returns an existing, or new data tracker.
	 */
	def getTracker(name: String): ITracker[String] =
		state.getTracker(name)

	/**
	 * Disposes of this objects allocated resources.
	 */
	def dispose() {
		state.dispose()
		actorSystem.actorSelection("*") ! PoisonPill
	}
}
