package com.cooper.osgi.speech.service

import akka.actor.{Terminated, Props, Actor}
import com.cooper.osgi.speech.service.Constants.{UpdateWriterCount, WriteFileMsg}
import akka.routing.{SmallestMailboxRoutingLogic, Router, ActorRefRoutee}
import com.cooper.osgi.utils.Logging
import scala.util.{Success, Failure}

class FileWriter() extends Actor {
	private[this] val log = Logging(this.getClass)

	def receive = {
		case WriteFileMsg(bucket, inStream, key) =>
			bucket.write(inStream, key) match {
				case Failure(error) => log.error(s"Error writing $key to file ${bucket.path}", error)
				case Success(_) => ()
			}

		case msg => log.error(s"FileWriter Got invalid message of $msg.")
	}
}

class FileRouter(
		writerInstances: Int
	) extends Actor {

	private[this] val log = Logging(this.getClass)

	private[this] var router = makeRouter(writerInstances)

	private[this] def makeRouter(n:Int) = {
		val routees = Vector.fill(n) {
			val r = context.actorOf(Props[FileWriter])
			context watch r
			ActorRefRoutee(r)
		}
		Router(SmallestMailboxRoutingLogic(), routees)
	}

	def receive = {
		case UpdateWriterCount(n) =>
			router = makeRouter(n)

		case work: WriteFileMsg =>
			router.route(work, sender())

		case Terminated(actor) =>
			router = router.removeRoutee(actor)
			val r = context.actorOf(Props[FileWriter])
			context watch r
			router = router.addRoutee(r)

		case msg => log.error(s"FileRouter Got invalid message of $msg.")
	}
}
