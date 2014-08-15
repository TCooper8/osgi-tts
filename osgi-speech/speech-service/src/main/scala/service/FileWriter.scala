package com.cooper.osgi.speech.service

import akka.actor.{Terminated, Props, Actor}
import akka.routing.{SmallestMailboxRoutingLogic, Router, ActorRefRoutee}
import scala.util.{Try, Success, Failure}
import com.cooper.osgi.io.IBucket
import java.io.{ByteArrayOutputStream, InputStream}

object FileMsg {
	trait Msg

	trait Reply

	case class UpdateWriterCount(n: Int) extends Msg

	case class WriteFileMsg(bucket: IBucket, inStream: InputStream, key: String) extends Msg

	case class PullFileToF(bucket: IBucket, filename: String, f: Array[Byte] => Any) extends Msg
}

class FileWriter(fileCachedField: SynchronizedBitField) extends Actor {

	private[this] val log =
		Utils.getLogger(this.getClass)

	private[this] val track =
		Utils.getTracker(Constants.trackerKey)

	private[this] def streamToByteArray(is: InputStream) = Try{
		val iBytes = is.available()
		Utils.using2(is, new ByteArrayOutputStream()) { case (is, os) =>
			val fBytes = Utils.copy(is, os)
			os.flush()

			if(iBytes != fBytes) {
				log.warn(s"Bytes copied do not match. StreamLength:$iBytes -> Copied:$fBytes")
				None
			} else {
				Option(os.toByteArray())
			}
		}
	}

	def receive = {
		/**
		 * Writes the stream to the bucket under the key.
		 */
		case FileMsg.WriteFileMsg(bucket, inStream, key) =>
			bucket.write(inStream, key) match {
				case Failure(error) =>
					log.error(s"Error writing $key to file ${bucket.path}", error)
					track.put(error.getClass.getName, 1l)

				case Success(_) =>
					track.put("FileWrites", 1l)
					fileCachedField.put(key.hashCode())
					()
			}
			inStream.close()

		/**
		 * Reads the filename to an array of bytes, and passes it into the function f.
		 */
		case FileMsg.PullFileToF(bucket, filename, f) =>
			bucket.read(filename).flatMap { node =>
				node.content.flatMap {
					content => streamToByteArray(content)
				}
			} match {
				case Success(Some(data)) =>
					val _ = f(data)
					track.put("PullFileToMap:StreamCopy:Success", 1)

				case Success(None) =>
					track.put("PullFileToMap:StreamCopy:Fail", 1)
					log.warn(
						s"""
						   |Copy failure for :
						   |Bucket: ${bucket.key}
						   |File: $filename
						 """.stripMargin
					)


				case Failure(err) =>
					track.put("PullFileToMap:StreamCopy:Fail", 1)

			}

		case msg =>
			log.error(s"FileWriter Got invalid message of $msg.")
			track.put(s"BadMsg:$msg", 1l)
	}
}

class FileRouter(
		writerInstances: Int,
		fileCachedField: SynchronizedBitField
	) extends Actor {

	private[this] val log =
		Utils.getLogger(this.getClass)

	private[this] val track =
		Utils.getTracker(Constants.trackerKey)

	private[this] var router =
		makeRouter(writerInstances)

	private[this] def makeRouter(n:Int) = {
		val routees = Vector.fill(n) {
			val r = context.actorOf(Props(
				classOf[FileWriter],
				fileCachedField
			))
			context watch r
			ActorRefRoutee(r)
		}
		Router(SmallestMailboxRoutingLogic(), routees)
	}

	def receive = {
		case FileMsg.UpdateWriterCount(n) =>
			router = makeRouter(n)

		case work: FileMsg.WriteFileMsg =>
			router.route(work, sender())

		case work: FileMsg.PullFileToF =>
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
