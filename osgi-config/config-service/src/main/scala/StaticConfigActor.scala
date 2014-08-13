package com.cooper.osgi.config.service
/*
import akka.actor.{Actor, ActorRef}
import akka.pattern.ask
import com.cooper.osgi.config.IConfigurable
import scala.concurrent.duration.FiniteDuration
import scala.util.Try

class StaticConfigActor(
		keeperPool: ActorRef,
		listener: ConfigProxy,
		config: IConfigurable,
		defaultData: Iterable[(String, Array[Byte])],
		linkedConfigMap: Map[String, String => Unit],
		tickTime: FiniteDuration
	) extends Actor {

	private[this] val log =
		Utils.getLogger(this)

	private[this] val track =
		Utils.getTracker(Constants.trackerKey)

	private[this] val encoding =
		"UTF-8"

	private[this] var keeper: ZooKeeper =
		getKeeper


	private[this] def getKeeper = {
		Try {

		}
	}
}
*/
