package com.cooper.osgi.tracking.service

import akka.actor._
import com.cooper.osgi.tracking.{ITrackerService, ITracker}
import scala.collection.mutable
import com.typesafe.config.{ConfigFactory, Config}
import com.cooper.osgi.tracking.service.Tracker
import scala.Some

case class Tracker[A](name: String) extends ITracker[A] {
	private[this] val cache: mutable.Map[A, Long] =
		mutable.Map[A, Long]()

	def put(k: A, v: Long) {
		cache.update(k, v + cache.getOrElse(k, 0l))
	}

	def get(key: A): Option[Long] =
		cache get key

	def iterable: Iterable[(A, Long)] =
		cache.toIterable
}

class TrackerService() extends ITrackerService {

	private[this] val actorSystem = getActorSystem

	private[this] val trackers: mutable.Map[String, ITracker[String]] =
		new mutable.HashMap[String, ITracker[String]]()
			with mutable.SynchronizedMap[String, ITracker[String]]

	private[this] def getActorSystem = {
		val loader = classOf[ActorSystem].getClassLoader()
		val config: Config = {
			val config = ConfigFactory.defaultReference(loader)
			config.checkValid(ConfigFactory.defaultReference(loader), "akka")
			config
		}
		ActorSystem("Com***REMOVED***SpeechService", config, loader)
	}

	def getTracker(name: String): ITracker[String] = {
		trackers get name match {
			case Some(tracker) => tracker
			case None =>
				val tracker: ITracker[String] = TypedActor(actorSystem).typedActorOf(
					TypedProps(
						classOf[ITracker[String]],
						Tracker[String](name)
					)
				)
				val _ = trackers.put(name, tracker)
				tracker
		}
	}

	def dispose() {
		actorSystem.actorSelection("*") ! PoisonPill
	}
}
