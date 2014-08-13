package com.cooper.osgi.config.service

import com.cooper.osgi.config.{IConfigWatcher, IConfigurable, IConfigService}
import akka.actor.{Props, PoisonPill, ActorSystem}
import com.typesafe.config.{ConfigFactory, Config}
import scala.collection.mutable
import org.apache.zookeeper.ZooKeeper
import scala.concurrent.{ExecutionContext, Await, Future}
import scala.util.{Success, Failure, Try}
import ExecutionContext.Implicits.global
import scala.concurrent.duration._
import com.cooper.osgi.tracking.ITrackerService

class ConfigService(trackerService: ITrackerService) extends IConfigService {

	/**
	 * This sets the tracker service for the rest of the components.
	 */
	Utils.setTrackerService(trackerService)

	private[this] val actorSystem = getActorSystem

	private[this] val keeperPool = actorSystem.actorOf(Props(
		classOf[ZooKeeperPool]
	))

	private[this] def getActorSystem = {
		val loader = classOf[ActorSystem].getClassLoader()
		val config: Config = {
			val config = ConfigFactory.defaultReference(loader)
			config.checkValid(ConfigFactory.defaultReference(loader), "akka")
			config
		}
		ActorSystem(this.getClass.getName.replace(".", ""), config, loader)
	}

	/**
	 * Attempts to create a new IConfigWatcher.
	 * @param config The configurable object.
	 * @param defaultData Some default data to use if none exists.
	 * @return Returns Success(watcher) or Failure(error) in the event of failure.
	 */
	def apply(
		config: IConfigurable,
		defaultData: Iterable[(String, String)]
			): Try[IConfigWatcher] = Try{

		ConfigProxy(
			keeperPool,
			actorSystem,
			config,
			defaultData,
			Constants.keeperTickTime
		)
	}

	def dispose() {
		actorSystem.actorSelection("*") ! PoisonPill
	}
}
