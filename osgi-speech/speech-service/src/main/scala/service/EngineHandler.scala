package com.cooper.osgi.speech.service

import akka.actor._
import scala.util.{Failure, Try}
import akka.routing._
import com.cooper.osgi.config.{IConfigService, IConfigurable}
import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import akka.util.Timeout
import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import com.ning.http.client.Response
import scala.Some
import akka.routing.Router
import akka.actor.Terminated

/**
 * Engine actor messages.
 */
object EngineMsg {
	trait Msg

	trait Reply

	case class CallEngine(voice: String, speak: String) extends Msg

	case class CallEngineReply(data: Try[Response]) extends Reply
}

/**
 * Represents an actor for a single engine handler.
 * @param name The name of the engine.
 * @param configService The configuration server.
 * @param configHost The configuration host.
 * @param configNode The configuration node to watch.
 */
class EngineHandler(
		name: String,
		configService: IConfigService,
		val configHost: String,
		val configNode: String
	) extends Actor with IConfigurable {

	private[this] val log =
		Utils.getLogger(name)

	private[this] val track =
		Utils.getTracker(Constants.trackerKey)

	/**
	 * Configuration keys
	 */

	private[this] val kHost = "host"
	private[this] val kAlias = "alias"
	private[this] val kVoices = "voices"
	private[this] val kTimeoutWarning = "timeoutWarning"

	/**
	 * Mutable configuration state.
	 */

	private[this] var engineHost = "localhost:8080"
	private[this] var engineAlias = "getTTSFile"
	private[this] var engineVoices = Set[String]()
	private[this] var timeoutWarning = 10

	/**
	 * Config watcher.
	 */
	private[this] val watcher = configService(
		this,
		Iterable(
			kHost -> engineHost,
			kAlias -> engineAlias,
			kVoices -> engineVoices.mkString(","),
			kTimeoutWarning -> timeoutWarning.toString
		)
	).toOption
	if (watcher.isEmpty)
		log.error("Watcher is undefined.")

	/**
	 * Maps configuration keys to functionality.
	 */
	private[this] val propHandleMap = Map(
		kHost -> { host:String =>
			if (host.startsWith("http://"))
				engineHost = host
			else engineHost = s"http://$host"
		},
		kAlias -> { alias:String =>
			if (alias.startsWith("/"))
				engineAlias = alias
			else engineAlias = s"/$alias"
		},
		kVoices -> { str: String =>
			Try{ parseList(str).toSet }.map {
				set => engineVoices = set
			}
		},
		kTimeoutWarning -> { str: String =>
			Try{ str.toInt }.map {
				v => timeoutWarning = v
			}
		}
	)

	private[this] def parseList(input: String) =
		input.split(",").map {
			s =>
				val ns = s.replaceAll("\\s+", "")
				if (ns == "") None else Some(ns)
		}.flatten

	private[this] def callEngine(voice:String, speak:String) = Try{
		import dispatch._

		// Create a uri for the engine to handle.
		val uri = s"$engineHost$engineAlias?voice=$voice&speak=${speak.replace(" ", "%20")}"

		// Create the http request.
		val service = url(uri)
		val task = Http(service)
		val resp = task()

		// Validate the output, raise any warnings.

		val rc = resp.getStatusCode()
		if (rc != 200) {
			log.error(s"Engine response error $rc from $name")
			track.put(s"EngineCall($name):Bad", 1l)
		}

		if (resp.getContentType == null) {
			val msg =
				s"""
				   |Engine response ContentType from $name is null.
				   |Request: voice=$voice, speak=$speak.
				   |Uri: $uri
				   |StatusCode: $rc
				 """.stripMargin
			log.warn(msg)
		}

		resp
	}

	def receive = {
		case EngineMsg.CallEngine(voice: String, speak: String) =>
			val duration = timeoutWarning seconds
			implicit val timeout = Timeout(duration)

			val task = Future{ callEngine(voice, speak) }
			val res = Try{Await.result(task, duration)}.flatten

			if (res.isFailure) {
				log.warn(s"Timeout warning triggered. Engine $name taking longer than expected to process msg")
				track.put(s"EngineCall($name):Bad", 1l)
			}
			else
				track.put(s"EngineCall($name):Good", 1l)

			sender ! EngineMsg.CallEngineReply(res)

		case ActorMsg.UpdateProps(props) =>
			log.info(s"Updating with $props")
			props.foreach {
				case (k, v) => propHandleMap.get(k).foreach{ _.apply(v) }
			}

		case msg =>
			log.error(s"Got invalid message of $msg.")
			track.put(s"BadMsg:$msg", 1l)
	}

	/**
	 * Callback to inform this object that updates have occurred.
	 * @param props The map of updates that have occurred.
	 */
	override def configUpdate(props: Iterable[(String, String)]) {
		self ! ActorMsg.UpdateProps(props)
	}

	override def postStop() {
		log.info(s"Shutting down $name.")
		watcher foreach { _.dispose() }
		super.postStop()
	}

	override def preRestart(reason: Throwable, message: Option[Any]) {
		log.info(s"Resetting $name.")
		message foreach { self forward _ }
		watcher foreach { _.dispose() }
	}
}

class EngineRouter(
		configService: IConfigService,
		val configHost: String,
		val configNode: String
	) extends Actor with IConfigurable {

	private[this] val log =
		Utils.getLogger(this)

	private[this] val track =
		Utils.getTracker(Constants.trackerKey)

	/**
	 * The configuration watcher.
	 */
	private[this] val watcher = configService(
		this,
		Nil
	).toOption

	/**
	 * The actor router for routing messages.
	 */
	private[this] var router: Router =
		Router(SmallestMailboxRoutingLogic(), Vector.empty[Routee])

	/**
	 * For tracking engines already in memory.
	 * - This is to prevent spinning up multiple config watchers for the same engine.
	 */
	private[this] val nameSet =
		mutable.Set[String]()

	/**
	 * Spawns a single engine handler.
	 * @param name The engine name.
	 * @return Returns a handler for a configurable remote engine.
	 */
	private[this] def spawnRoutee(name:String) = {
		context.actorOf(Props(
			classOf[EngineHandler],
			name,
			configService,
			configHost,
			configNode + s"/$name"
		))
	}

	/**
	 * Fills the router with the routees found in the given Iterable.
	 * @param engines Contains the routees information.
	 */
	private[this] def spawnRouter(engines: Iterable[(String,String)]) {
		engines.foreach {
			case (engineName, _) =>
				log.info(s"Spawning $engineName")
				if (nameSet contains engineName) ()
				else {
					Try {
						val r = spawnRoutee(engineName)
						context watch r
						router = router.addRoutee(r)
						nameSet add engineName
					} match {
						case Failure(err) =>
							log.error(s"Error spawning engine $engineName", err)
						case _ => ()
					}
				}
		}
	}

	def receive = {
		case work: EngineMsg.CallEngine =>
			router.route(work, sender())

		case Terminated(actor) =>
			val name = actor.path.name
			nameSet remove name
			router = router.removeRoutee(actor)

			log.info(s"EngineHandler $name is terminated, rerouting work.")

			val r = spawnRoutee(name)
			context watch r
			router = router.addRoutee(r)

		case ActorMsg.UpdateProps(props) =>
			log.info(s"Updating with $props")
			//this.engines ++= props
			spawnRouter(props)

		case msg =>
			log.error(s"Got invalid message of $msg.")
			track.put(s"BadMsg:$msg", 1l)
	}

	/**
	 * Callback to inform this object that updates have occurred.
	 * @param props The map of updates that have occurred.
	 */
	def configUpdate(props: Iterable[(String, String)]) {
		self ! ActorMsg.UpdateProps(props)
	}

	override def postStop() {
		watcher foreach { _.dispose() }
		super.postStop()
	}
}
