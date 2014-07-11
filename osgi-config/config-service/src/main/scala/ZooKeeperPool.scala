package com.cooper.osgi.config.service

/*
	Local package imports.
  */

import Constants.{futureTimeout, keeperTickTime}

import scala.collection.mutable
import org.apache.zookeeper.ZooKeeper
import akka.actor.{ReceiveTimeout, Actor}
import scala.concurrent.{Await, Future, ExecutionContext}
import ExecutionContext.Implicits.global
import scala.util.{Failure, Success, Try}
import org.apache.zookeeper.ZooKeeper.{States => KeeperState}
import com.cooper.osgi.config.{ConfigException, ConfigZooKeeperAuthFailed}

object ZooKeeperPool {
	trait Msg
	trait Reply
	case class GetKeeper(host:String) extends Msg
	case class GetKeeperReply(reply: Try[ZooKeeper]) extends Reply
}

class ZooKeeperPool() extends Actor {

	this.context.setReceiveTimeout(keeperTickTime)

	private[this] val keepers: mutable.Map[String, ZooKeeper] =
		new mutable.HashMap[String, ZooKeeper]()
			with mutable.SynchronizedMap[String, ZooKeeper]

	private[this] def awaitConnection(keeper: ZooKeeper, host:String): Try[ZooKeeper] = Try {
		val task = Future {
			while (keeper.getState == KeeperState.CONNECTING)
				Thread.sleep(10)
			keeper
		}

		handleState(
			Await.result(task, futureTimeout),
			host
		)
	}.flatten

	private[this] def handleState(keeper: ZooKeeper, host:String): Try[ZooKeeper] = {
		keeper.getState() match {
			case KeeperState.AUTH_FAILED =>
				// Authentication failure cannot be recovered.
				Failure {
					ConfigZooKeeperAuthFailed(message = s"Cannot authenticate connection for ZooKeeper@$host")
				}
			case KeeperState.CLOSED =>
				makeKeeper(host)

			case KeeperState.CONNECTED =>
				Success(keeper)

			case KeeperState.CONNECTING =>
				awaitConnection(keeper, host)

			case _ =>
				Failure {
					ConfigException(message = s"Unhandled state occurred for ZooKeeper@$host")
				}
		}
	}

	private[this] def makeKeeper(host:String) = Try{
		val keeper = new ZooKeeper(host, keeperTickTime.toMillis.toInt, null)
		val handled = handleState(keeper, host)

		if (handled.isFailure)
			keeper.close()

		handled
	}.flatten

	private[this] def getKeeper(host:String): Try[ZooKeeper] = Try{
		keepers get host match {
			case Some(keeper) => handleState(keeper, host)
			case None => makeKeeper(host)
		}
	}.flatten

	private[this] def cleanKeepers() {
		keepers.map {
			case (host, keeper) =>
				if (keeper.getState() != KeeperState.CONNECTED) {
					val _ = Try{ keeper.close() }
					Some(host)
				}
				else None
		}.flatten.foreach {
			host => keepers remove host
		}
	}

	def receive = {
		case ZooKeeperPool.GetKeeper(host) =>
			val task = Future{getKeeper(host)}
			Try {
				val out = Await.result(task, futureTimeout)
				sender ! ZooKeeperPool.GetKeeperReply(out)
			}

		case ReceiveTimeout =>
			this.cleanKeepers()
	}
}
