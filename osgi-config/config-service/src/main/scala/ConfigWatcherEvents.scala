package com.cooper.osgi.config.service

import org.apache.zookeeper.data.Stat
import org.apache.zookeeper.WatchedEvent

/**
 * This object contains event messages that represent different ZooKeeper events.
 */
object ConfigWatcherEvents {
	trait Msg
	case class PutDataMsg(key: String, data: String) extends Msg
	case class ProcessMsg(event: WatchedEvent) extends Msg
	case class ProcessResultMsg(rc: Int, path: String, ctx: Object, stat: Stat) extends Msg
	case class ExistsMsg(data: Array[Byte]) extends Msg
}
