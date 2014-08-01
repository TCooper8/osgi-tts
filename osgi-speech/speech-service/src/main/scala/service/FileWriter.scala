package com.cooper.osgi.speech.service

import akka.actor.{Terminated, Props, Actor}
import com.cooper.osgi.speech.service.Constants.{UpdateWriterCount, WriteFileMsg}
import akka.routing.{SmallestMailboxRoutingLogic, Router, ActorRefRoutee}
import scala.util.{Success, Failure}

class FileWriter() extends Actor {

	private[this] val log =
		Utils.getLogger(this.getClass)

	private[this] val track =
		Utils.getTracker(Constants.trackerKey)

	def receive = {
		case WriteFileMsg(bucket, inStream, key) =>
			bucket.write(inStream, key) match {
				case Failure(error) =>
					log.error(s"Error writing $key to file ${bucket.path}", error)
					track.put(error.getClass.getName, 1l)

				case Success(_) =>
					track.put("FileWrites", 1l)
					()
			}
			inStream.close()

		case msg =>
			log.error(s"FileWriter Got invalid message of $msg.")
			track.put(s"BadMsg:$msg", 1l)
	}
}

class FileRouter(
		writerInstances: Int
	) extends Actor {

	private[this] val log =
		Utils.getLogger(this.getClass)

	private[this] val track =
		Utils.getTracker(Constants.trackerKey)

	private[this] var router =
		makeRouter(writerInstances)

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

		case msg =>
			log.error(s"FileRouter got invalid message of $msg.")
			track.put(s"BadMsg:$msg", 1l)
	}
}
