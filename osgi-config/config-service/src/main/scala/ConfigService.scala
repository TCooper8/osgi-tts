package com.cooper.osgi.config.service

import com.cooper.osgi.config.{IConfigWatcher, IConfigurable, IConfigService}
import scala.concurrent.duration.FiniteDuration
import akka.actor.{PoisonPill, ActorSystem}
import com.typesafe.config.{ConfigFactory, Config}
import com.cooper.osgi.utils.{MaybeLog, StatTracker, Logging}
import scala.collection.mutable
import org.apache.zookeeper.ZooKeeper
import scala.concurrent.{ExecutionContext, Await, Future}
import scala.util.{Success, Failure, Try}
import ExecutionContext.Implicits.global
import scala.concurrent.duration._


class ConfigService extends IConfigService {
	private[this] val log = Logging(this.getClass)
	private[this] val track = StatTracker(Constants.trackerKey)
	private[this] val maybe = MaybeLog(log, track)

	private[this] val connTimeout = 2 seconds

	private[this] val actorSystem = getActorSystem

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
	 * @param tickTime The configuration refresh time.
	 * @return Returns Some(watcher) or None in the event of failure.
	 */
	def apply(
		config: IConfigurable,
		defaultData: Iterable[(String, String)],
		tickTime: FiniteDuration
			): Option[IConfigWatcher] = maybe{

		getKeeper(config.configHost, tickTime) map {
			keeper =>
				new ConfigProxy(
					keeper,
					actorSystem,
					config,
					defaultData,
					tickTime
				)
		}
	}.flatten

	def dispose() {
		actorSystem.actorSelection("*") ! PoisonPill
		keepers foreach { case (k,v) => v.close() }
	}
}
