package com.cooper.osgi.speech.service

import akka.actor.{Terminated, Props, Actor}
import akka.routing.{SmallestMailboxRoutingLogic, Router, ActorRefRoutee}
import scala.util.{Try, Success, Failure}
import com.cooper.osgi.io.IBucket
import java.io.{ByteArrayOutputStream, InputStream}

object FileMsg {
	trait Msg

	trait Reply

	/**
	 * Used for a FileWriterRouter to update it's writer count.
	 * @param n The number of writers to use.
	 */
	case class UpdateWriterCount(n: Int) extends Msg

	/**
	 * A message with a stream of data that needs to be written to a file.
	 * - This will either open a file and replace the data, or it will create a new file.
	 *
	 * @param bucket The bucket to write to.
	 * @param inStream The stream of data.
	 * @param key The new file name.
	 */
	case class WriteFileMsg(bucket: IBucket, inStream: InputStream, key: String) extends Msg

	/**
	 * This is meant to lazily pull data from a file to a cache, using the f: Array[Byte] => Any method.
	 * @param bucket The bucket to read from.
	 * @param filename The file to look for.
	 * @param f The callback function for when the data has been read.
	 */
	case class PullFileToF(bucket: IBucket, filename: String, f: Array[Byte] => Any) extends Msg

	/**
	 * This is meant to lazily pull an entire bucket of data into a cache.
	 * @param bucket The bucket to dig through.
	 * @param f The callback function for when the data has been read.
	 */
	case class PullBucketNodesToF(bucket: IBucket, pred: String => Boolean, f: (String, Array[Byte]) => Any) extends Msg
}

/**
 * This class is meant to handle blocking IO write requests.
 *
 * This class also handles other blocking IO requests.
 */
class FileWriter() extends Actor {

	private[this] val log =
		Utils.getLogger(this.getClass)

	private[this] val track =
		Utils.getTracker(Constants.trackerKey)

	/**
	 * Attempts to read the entire given stream to a byte array.
	 * This is especially useful when dealing with remote file systems.
	 *
	 * Example: S3 bucket.read methods return a network stream to the actual data.
	 *
	 * It's better to allow a blocking read-fully operation to progress on a parallel thread,
	 * 	rather than block the main context.
	 *
	 * @param is The input stream to read from.
	 * @return Returns the entire array of byte data from the stream.
	 */
	private[this] def streamToByteArray(is: InputStream) = Try{
		val iBytes = is.available()

		Utils.using2(is, new ByteArrayOutputStream()) { case (is, os) =>
			val fBytes = Utils.copy(is, os)
			os.flush()

			if(iBytes != fBytes) {
				log.warn(s"Bytes copied do not match. StreamLength:$iBytes -> Copied:$fBytes")
				None
			} else {
				Option(os.toByteArray)
			}
		}
	}

	/**
	 * This will pull a file from the given bucket into memory, then pass the array to the callback function.
	 * @param bucket The bucket to read from.
	 * @param key The key associated with the desired file
	 * @param fun The callback function for when the bytes have been read.
	 */
	private[this] def pullFileToF(bucket: IBucket, key: String, fun: Array[Byte] => Any) {
		bucket.read(key).flatMap {
			node =>
				node.content.flatMap {
					content =>
						streamToByteArray(content)
				}
		} match {
			case Success(Some(data)) =>
				val _ = fun(data)
				track.put("PullFileToMap:StreamCopy:Success", 1)

			case Success(None) =>
				track.put("PullFileToMap:StreamCopy:Fail", 1)
				log.warn(
					s"""
				   |Copy failure for :
				   |Bucket: ${bucket.key}
				   |File: $key
				 """.stripMargin
				)

			case Failure(err) =>
				track.put("PullFileToMap:StreamCopy:Fail", 1)
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
					()
			}

			inStream.close()

		case FileMsg.PullBucketNodesToF(bucket, pred, fun) =>
			bucket.listNodes.foreach { nodes =>
			  	nodes.foreach { node =>
				  	val key = node.key

					if (pred(key))
						pullFileToF(bucket, key, arr => fun(key, arr))
				}
			}

		/**
		 * Reads the filename to an array of bytes, and passes it into the function f.
		 */
		case FileMsg.PullFileToF(bucket, key, fun) => pullFileToF(bucket, key, fun)

		case msg =>
			log.error(s"FileWriter Got invalid message of $msg.")
			track.put(s"BadMsg:$msg", 1l)
	}
}

/**
 * This class is meant to act as a router for blocking IO calls.
 * @param writerInstances The number of writer instances to use.
 */
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
			val r = context.actorOf(Props(
				classOf[FileWriter]
			))
			context watch r
			ActorRefRoutee(r)
		}
		Router(SmallestMailboxRoutingLogic(), routees)
	}

	def receive = {
		case FileMsg.UpdateWriterCount(n) =>
			router = makeRouter(n)

		case work: FileMsg.Msg =>
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
