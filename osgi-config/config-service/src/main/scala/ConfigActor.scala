package com.cooper.osgi.config.service

import Constants._

import akka.actor.{ActorRef, PoisonPill, ReceiveTimeout, Actor}
import akka.pattern.ask
import com.cooper.osgi.config.IConfigurable
import scala.concurrent.duration.FiniteDuration
import com.cooper.osgi.utils.{MaybeLog, StatTracker, Logging}
import org.apache.zookeeper._
import org.apache.zookeeper.ZooDefs.Ids
import org.apache.zookeeper.data.Stat
import com.cooper.osgi.config.service.ConfigWatcherEvents.{PutDataMsg, ProcessMsg, ExistsMsg, ProcessResultMsg}
import org.apache.zookeeper.Watcher.Event.KeeperState
import org.apache.zookeeper.KeeperException.Code
import scala.util.{Success, Try, Failure}
import scala.collection.JavaConversions._
import scala.concurrent.Await

/**
 * This acts as a ZooKeeper watch implemtation.
 * @param listener The listener to use.
 * @param config The IConfigurable to use.
 * @param defaultData The default data to load into configuration.
 * @param tickTime The config refresh time.
 */
class ConfigActor(
		keeperPool: ActorRef,
  		listener: ConfigProxy,
		config: IConfigurable,
		defaultData: Iterable[(String, Array[Byte])],
		tickTime: FiniteDuration
	) extends Actor {

	private[this] val log = Logging(this)
	private[this] val track = StatTracker(Constants.trackerKey)
	private[this] val maybe = MaybeLog(log, track)

	private[this] val encoding = "UTF-8"

	private[this] var keeper: ZooKeeper = getKeeper

	/**
	 * The ZooKeeper to listen to.
	 * 	- If this crashes for any reason, this actor should die and respawn.
	*/

	this.init()

	keeper.register(listener)

	/**
	 * Initializes data and loads default data into configuration.
	 */
	private[this] def init() {
		// Ensure that the entire node path exists.
		val nodes = config.configNode.split('/')
		if (nodes.length > 1)
			nodes.foldLeft("") {
				case (acc, node) =>
					val path = s"$acc/$node".replaceAll("(/)+", "/")
					getStat(path) match {
						case None => Try{createNodeUnsafe(path, Array[Byte]())}
						case _ => ()
					}
					path
			}

		// Pull in any properties already present, else load default data.

		val newProps = keeper.getChildren(config.configNode, true).toList.flatMap {
			child =>
				val node = toNode(child)
				getStat(node) match {
					case Some(stat) =>
						val data = keeper.getData(node, true, stat)
						if (data != null) Some(child -> new String(data, encoding))
						else None

					case None => None
				}
		}

		defaultData foreach {
			case (child, data) =>
				val node = toNode(child)
				getStat(node) match {
					case None =>
						log.info(s"Pushing node $node -> $data")
						maybe { createNodeUnsafe(node, data) }
					case _ => ()
				}
		}

		config.configUpdate(newProps)
	}

	private[this] def getKeeper: ZooKeeper = {
		Try {
			val task = keeperPool ? ZooKeeperPool.GetKeeper(config.configHost)
			Await.result(task, futureTimeout) match {
				case ZooKeeperPool.GetKeeperReply(reply) => reply
				case _ => Failure(null)
			}
		}.flatten match {
			case Success(keeper) => keeper
			case Failure(error) =>
				log.error("", error)
				null
		}
	}

	/**
	 * Represents the current nodes' stats.
	 */
	private[this] var curStats = getPropStats()

	checkUpdate()

	/**
	 * Converts a given key to an absolute node.
	 */
	private[this] def toNode (key: String) = s"${config.configNode}/$key"

	/**
	 * Peels the last node in the sequence off of a node path.
	 */
	private[this] def toKey (node: String) = node.split('/').last

	private[this] def getStat(node: String) = {
		Try {
			Option(keeper.exists(node, true))
		} match {
			case Success(stat) => stat
			case Failure(err) => log.error(s"Failed to retrieve $node", err); None
		}
	}

	private[this] def getPropStats() =
		keeper.getChildren(config.configNode, true).toList.flatMap {
			child => getStat(toNode(child)) map {
				stat => (child -> stat)
			}
		}.toMap

	private[this] def createNodeUnsafe(node: String, data: Array[Byte]) {
		keeper.create(node, data, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT)
	}

	private[this] def createNodeSafe(node: String, data: Array[Byte], stat: Stat) = maybe {
		if (stat == null) createNodeUnsafe(node, data)
	}

	private[this] def setDataUnsafe(node: String, data: Array[Byte], version: Int) {
		keeper.setData(node, data, version)
	}

	private[this] def setDataSafe(node: String, data: Array[Byte], stat: Stat) = maybe {
		// If no node exists, create a new one.
		if (stat == null) createNodeUnsafe(node, data)
		else setDataUnsafe(node, data, stat.getVersion())
	}

	private[this] def checkUpdate() {
		val newStats = getPropStats()

		val diffStats = newStats.flatMap {
			case (child, newStat) => curStats get child match {
				case Some(curStat) =>
					if (newStat.getVersion() > curStat.getVersion())
						Some(child -> newStat)
					else None
				case None => Some(child -> newStat)
			}
		}

		val newProps = diffStats.flatMap {
			case (child, stat) =>
				val node = toNode(child)
				maybe{
					val data = keeper.getData(node, true, stat)
					(child -> new String(data, encoding))
				}
		}

		curStats = newStats
		config.configUpdate(newProps)
	}

	def receive = {
		case ProcessMsg(event) => process(event)
		case ExistsMsg(data) => exists(data)
		case ProcessResultMsg(rc, path, ctx, stat) => processResult(rc, path, ctx, stat)

		case PutDataMsg(key, data) =>
			val node = toNode(key)
			getStat(node) foreach {
				stat => setDataSafe(node, data.getBytes(encoding), stat)
			}

		case ReceiveTimeout => checkUpdate()
		case PoisonPill => //keeper.close()
	}

	/**
	 * Processes a zookeeper WatchedEvent.
	 * @param event The event to process.
	 */
	private[this] def process(event: WatchedEvent) {
		val path = event.getPath()

		if (event.getType() == Watcher.Event.EventType.None) {
			event.getState() match {
				case KeeperState.SyncConnected => ()

				case KeeperState.Expired =>
					this.keeper = getKeeper
					//listener.closing(KeeperException.Code.SESSIONEXPIRED.intValue())

				case _ => ()
			}
		}
		else {
			Option(path) map { path =>
				if (path == config.configNode) keeper.exists(path, true, listener, null)
				else {
					keeper.exists(path, true, listener, null)
				}
			}
		}
	}

	/**
	 * Processes a zookeeper callback result.
	 * @param rc The return code to process.
	 * @param path The node path associated with the callback.
	 * @param context Unknown what this is for, not documented at the time of this writing.
	 * @param stat The zookeeper Stat associated with the node.
	 */
	private[this] def processResult(rc: Int, path: String, context: Object, stat: Stat) {
		log.info(s"Got process result for ${Code.get(rc)} -> $path")

		(Code.get(rc) match {
			case Code.OK => Some(true)
			case Code.NONODE => Some(false)
			case Code.SESSIONEXPIRED => None
			case Code.NOAUTH => listener.closing(rc); Some(true)
			case _ =>
				keeper.exists(config.configNode, true, listener, null)
				None
		}) map (exists => {
			if (exists) maybe {
				checkUpdate()
			}
		})
	}

	/**
	 * Processes a zookeeper exists callback.
	 * @param data The data to validate.
	 */
	def exists(data: Array[Byte]) {
	}

	override def preRestart(reason: Throwable, message: Option[Any]) {
		log.error("Actor restarting.", reason)
		super.preRestart(reason, message)
	}

	override def postStop() {
		super.postStop()
	}
}
