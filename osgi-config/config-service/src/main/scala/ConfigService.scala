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

	Utils.setTrackerService(trackerService)

	private[this] val log = Utils.getLogger(this)
	private[this] val track = Utils.getTracker(Constants.trackerKey)

	private[this] def maybe[A](expr: => A): Option[A] = maybe("")(expr)
	private[this] def maybe[A](msg: String = "")(expr: => A): Option[A] = {
		Try{ expr } match {
			case Success(m) => Option(m)
			case Failure(err) =>
				log.error(msg, err)
				track.put(err.getClass.getName, 1l)
				None
		}
	}

	private[this] val connTimeout = 2 seconds

	private[this] val actorSystem = getActorSystem

	private[this] val keeperPool = actorSystem.actorOf(Props(
		classOf[ZooKeeperPool]
	))

	private[this] val keepers: mutable.Map[String, ZooKeeper] =
		new mutable.HashMap[String, ZooKeeper]()
		  with mutable.SynchronizedMap[String, ZooKeeper]

	private[this] def getActorSystem = {
		val loader = classOf[ActorSystem].getClassLoader()
		val config: Config = {
			val config = ConfigFactory.defaultReference(loader)
			config.checkValid(ConfigFactory.defaultReference(loader), "akka")
			config
		}
		ActorSystem(this.getClass.getName.replace(".", ""), config, loader)
	}

	private[this] def cleanKeepers() {
		keepers.map {
			case (k, v) =>
				v.getState() match {
					case ZooKeeper.States.CONNECTED => None
					case _ => v.close(); Some(k)
				}
		}.flatten.map {
			keepers.remove(_)
		}
	}

	private[this] def getKeeper(host: String, tickTime: FiniteDuration) = {
		def makeNewKeeper() {
			val keeper = new ZooKeeper(host, tickTime.toMillis.toInt, null)
			val task = Future {
				while (keeper.getState() == ZooKeeper.States.CONNECTING) {
				}
				keeper.getState()
			}
			Try {
				Await.result(task, connTimeout)
			} match {
				case Failure(err) =>
					keeper.close()
					log.error(s"Unable to connect to ZooKeeper $host")

				case Success(state) =>
					state match {
						case ZooKeeper.States.CONNECTED => keepers.put(host, keeper)
						case _ =>
							keeper.close()
							log.error(s"Unable to connect to ZooKeeper $host")
					}
			}
		}

		cleanKeepers()

		keepers.get(host) match {
			case None =>
				makeNewKeeper()
				keepers.get(host)

			case Some(keeper) =>
				keeper.getState() match {
					case ZooKeeper.States.CONNECTED => Some(keeper)
					case _ =>
						makeNewKeeper()
						keepers.get(host)
				}
		}
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
		keepers foreach { case (k,v) => v.close() }
	}
}
