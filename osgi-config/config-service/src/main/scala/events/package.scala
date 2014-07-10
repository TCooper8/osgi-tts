package com.cooper.osgi.config.service

import org.apache.zookeeper.ZooKeeper
import scala.util.Try

package object events {
	trait Msg
	trait Reply

	case class GetKeeper(host: String) extends Msg
	case class GetKeeperReply(keeper: Try[ZooKeeper]) extends Reply
}
