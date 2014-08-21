package com.cooper.osgi.config.service

import akka.actor._

import com.cooper.osgi.config.{IConfigurable, IConfigWatcher}
import org.apache.zookeeper.AsyncCallback.StatCallback
import org.apache.zookeeper.{WatchedEvent, Watcher}
import scala.concurrent.duration.FiniteDuration
import org.apache.zookeeper.data.Stat
import scala.util.{Failure, Success, Try}
import com.cooper.osgi.config.service.ConfigWatcherEvents._
import java.io.InputStream

/**
 * This is a proxy class for a ConfigActor.
 * @param actorSystem The actor system to use.
 * @param config The IConfigurable to use.
 * @param defaultData The default data to load into configuration.
 * @param tickTime The config refresh time.
 */
case class ConfigProxy(
		keeperPool: ActorRef,
		actorSystem: ActorSystem,
		config: IConfigurable,
		defaultData: Iterable[(String, String)],
		tickTime: FiniteDuration
	) extends IConfigWatcher with
		IConfigListener with
		Watcher with
		StatCallback
	{

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

	val host: String = config.configHost

	val node: String = config.configNode

	private[this] val actor = actorSystem.actorOf(Props(
		classOf[DynamicConfigActor],
		keeperPool,
		this,
		config,
		defaultData map { case (k, v) => (k -> v.getBytes()) },
		tickTime
	))

	/**
	 * Pushes a String -> Array[Byte] to the configuration.
	 * @param key The key to push the data to.
	 * @param data The actual data to push.
	 */
	def putData(key: String, data: String) {
		maybe { actor ! PutDataMsg(key, data) }
	}

	/**
	 * Cleans up any resources allocated to this object.
	 */
	def dispose() {
		closing(0)
	}

	/**
	 * Alerts this object to shutdown.
	 * @param rc The return code.
	 */
	def closing(rc: Int) {
		log.info(s"Shutting down config watcher for ${config.configNode}@${config.configHost}.")
	}

	/**
	 * Takes a zookeeper exists callback and sends it to the watch actor.
	 * @param data The data to validate.
	 */
	def exists(data: Array[Byte]) {
		maybe{ actor ! ExistsMsg(data) }
	}

	/**
	 * Attempts to parse the contents of an InputStream and push it into configuration.
	 * - Parses the InputStream using 'typesafe.config.ConfigFactory.parse'.
	 * @param inStream The stream of data.
	 */
	def putData(rootNode: String, inStream: InputStream): Try[Unit] = Try{
		NodeStructure.parse(rootNode, inStream).map { nodes =>
			actor ! PutNodesMsg(nodes)
		}
	}.flatten

	/**
	 * Takes a zookeeper WatchedEvent and sends it to the watch actor.
	 * @param event The event to process.
	 */
	def process(event: WatchedEvent) {
		maybe { actor ! ProcessMsg(event) }
	}

	/**
	 * Takes a zookeeper callback result and sends it to the watch actor.
	 * @param rc The return code to process.
	 * @param path The node path associated with the callback.
	 * @param context Unknown what this is for, not documented at the time of this writing.
	 * @param stat The zookeeper Stat associated with the node.
	 */
	def processResult(rc: Int, path: String, context: Object, stat: Stat) {
		maybe{ actor ! ProcessResultMsg(rc, path, context, stat) }
	}
}
