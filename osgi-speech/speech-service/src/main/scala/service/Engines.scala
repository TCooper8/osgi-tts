package com.cooper.osgi.speech.service

import com.cooper.osgi.config.{IConfigurable, IConfigService}
import akka.actor._
import scala.util.Try
import com.ning.http.client.Response
import scala.concurrent.{duration, ExecutionContext, Await, Future}
import akka.util.Timeout
import akka.routing.{Routee, SmallestMailboxRoutingLogic}
import scala.concurrent.duration._
import java.net.URL
import scala.util.Failure
import scala.Some
import akka.routing.Router
import scala.util.Success
import akka.actor.Terminated
import scala.collection.mutable
import com.cooper.osgi.speech.service.EngineMsg.CallEngineReply
import ExecutionContext.Implicits.global

case class EngineNotAvailable(message: String = null, cause: Throwable = null) extends Exception

/**
 * The following Url is to generate a valid Url from a given string.
 */

trait Url
case object Url {
	private[this] case class UrlInternal(path: String) extends Url {
		final override def toString = path
	}

	def apply(path: String): Try[Url] =
		Try{
			val url = new URL(path)
			UrlInternal(url.toString)
		}
}

/**
 * Engine actor messages.
 */
object EngineMsg {
	trait Msg

	trait Reply

	case class CallEngine(voice: String, speak: String) extends Msg

	case class CallEngineReply(data: Try[Response]) extends Reply

	case class GetVoices() extends Msg

	case class UpdateVoices(voices: Set[String], oldVoices: Set[String]) extends Reply
}

/**
 * This class is meant to act as an actor for a single engine connection.
 * @param name The name of this engine.
 * @param engineUrl The valid url for querying.
 * @param timeoutWarning The timeout for this engine's msg queries.
 *                       - Note: This timeout should be less than the sender's timeout.
 */
class EngineHandler(
	name: String,
	engineUrl: Url,
	timeoutWarning: FiniteDuration
		) extends Actor {

	private[this] val log =
		Utils.getLogger(name)

	private[this] val track =
		Utils.getTracker(Constants.trackerKey)

	private[this] def callEngine(voice:String, speak:String) = Try{
		import dispatch._

		// Create a uri for the engine to handle.
		val uri = s"$engineUrl?voice=$voice&speak=${speak.replace(" ", "%20")}"

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

	def receive: Receive = {
		case EngineMsg.CallEngine(voice: String, speak: String) =>
			implicit val timeout = Timeout(timeoutWarning)

			val task = Future{ callEngine(voice, speak) }
			val res = Try{Await.result(task, timeoutWarning)}.flatten

			if (res.isFailure) {
				log.warn(s"Timeout warning triggered. Engine $name taking longer than expected to process msg")
				track.put(s"EngineCall($name):Bad", 1l)
			}
			else
				track.put(s"EngineCall($name):Good", 1l)

			sender ! EngineMsg.CallEngineReply(res)

		case msg =>
			log.error(s"Got invalid message of $msg.")
			track.put(s"BadMsg:$msg", 1l)
	}

	override def postStop() {
		log.warn(s"Shutting down $name: $self.")
		super.postStop()
	}

	override def preRestart(reason: Throwable, message: Option[Any]) {
		log.warn(s"Resetting $name: $self.")
		message foreach { self.forward }
	}
}

/**
 * This class is meant to act as a connection router.
 * - It manages multiple connections to a single host.
 *
 * This class supports an IConfiguration of: example:
 * 	name {
 * 		protocol = "http|tcp|udp|https"
 * 		host = "_"
 * 		ports = "8080, 8081-8084, 8084-8087, 8088"
 * 		alias = "getFile"
 * 		timeoutWarning = 8 seconds
 * 		voices = "Crystal, Mike"
 * 	}
 * @param name
 * @param configService
 * @param parent
 * @param configHost
 * @param configNode
 */
class EngineRouter(
	name: String,
	configService: IConfigService,
	parent: ActorRef,
	val configHost: String,
	val configNode: String
		) extends Actor with IConfigurable  {

	private[this] val log =
		Utils.getLogger(this)

	private[this] val track =
		Utils.getTracker(Constants.trackerKey)

	/**
	 * Configuration keys.
	 */

	private[this] val kProtocol = "protocol"
	private[this] val kHost = "host"
	private[this] val kPorts = "ports"
	private[this] val kAlias = "alias"
	private[this] val kTimeoutWarning = "timeoutWarning"
	private[this] val kVoices = "voices"

	/**
	 * Configuration values.
	 */

	private[this] var protocol = "http"
	private[this] var host = ""
	private[this] var ports = List[Int]()
	private[this] var alias = ""
	private[this] var timeoutWarning: FiniteDuration = 10.seconds
	private[this] var voices = Set[String]()

	/**
	 * This will map configuration keys to functionality within the actor.
	 */
	private[this] val propHandleMap = Map(
		kProtocol -> { v:String =>
			this.protocol = v
		},
		kHost -> { v: String =>
			this.host = v
		},
		kPorts -> { v: String =>
			// Attempt to parse the ports into a list.
		  	// Parsing will split by ',', then by '-' to generate a range.
		  	// Ex: "0, 1, 2 - 6, 6" -> [0, 1, 2, 3, 4, 5, 6]
		  	// - Partial parse failure is tolerated, but an error will be raised.

			val parts = v.split(',').flatMap { part =>
				part.split('-')
				.map { _.trim() }
				.flatMap { s =>
					Try{ s.toInt } match {
						case Success(i) => List(i)
						case Failure(err) =>
							log.error(
								s"""
								   | Unable to parse part $s from $this config key: ports.
								   | Please fix config node $configNode/$kPorts to a valid parsable list.
								   | (ex: "0, 1, 2-5, 5") -> [0, 1, 2, 3, 4, 5]
								 """.stripMargin)
							Nil
					}
				}
				.foldLeft (List[Int]()) { case (acc, i) =>
					acc match {
						case Nil => List(i)
						case h::t => acc ++ (h+1 to i)
					}
				}
			}
			this.ports = parts.toSet.toList
		},
		kAlias -> { v: String =>
			this.alias = v
		},
		kTimeoutWarning -> { v: String =>
			Try{
				val dur = Duration.apply(v)
				FiniteDuration(dur.toNanos, duration.NANOSECONDS)
			} match {
				case Success(result) =>
					this.timeoutWarning = result
				case Failure(err) =>
					log.error(s"Unable to parse Duration from $v", err.getMessage())
			}
		},
		kVoices -> { v: String =>
		  	// Attempt to parse the voices into a list.
		  	// Parsing will split by ',', and will lower case the voices."

			val parts =
				v.split(',')
				.map { _.trim() }

			val newVoices = parts.toSet

			// We must inform our parent that a voice update has occurred.
			parent ! EngineMsg.UpdateVoices(newVoices, this.voices)
			this.voices = newVoices
		}
	)

	/**
	 * The configuration watcher.
	 */
	private[this] val watcher = configService(
		this,
		Iterable(
			kProtocol -> protocol,
			kHost -> host,
			kPorts -> "",
			kAlias -> alias,
			kTimeoutWarning -> "8 seconds",
			kVoices -> ""
		)
	)
	watcher match {
		case Failure(err) =>
			log.error("Watcher failed to construct.", err)
		case _ => ()
	}

	/**
	 * The actor router for routing messages.
	 */
	private[this] var router: Router =
		Router(SmallestMailboxRoutingLogic(), Vector.empty[Routee])

	/**
	 * Spawns a single engine handler.
	 * @param port The port will determine the engine name.
	 * @return Returns a handler for a configurable remote engine.
	 */
	private[this] def spawnRoutee(name: String, port:Int) =
		// Attempt to structure a valid Url for the new engine.
		Url(s"$protocol://$host:$port/$alias") match {
			case Success(url) => Try {

				context.actorOf(Props(
					classOf[EngineHandler],
					name,
					url,
					timeoutWarning
				))
			}
			case Failure(e) => Failure(e)
		}

	private[this] def spawnRouter() {
		// Clear all routees.
		router.routees.foreach { r =>
			router = router.removeRoutee(r)
			r.send(PoisonPill, self)
		}

		// For each port in this engine, spin up a new routee.
		ports.foreach { port =>
			val name = s"$host:$port"
			Try {
				log.info(s"Spawning $name")
				spawnRoutee(name, port)
				.map { r =>
					context watch r
					router = router.addRoutee(r)
				}
			}.flatten match {
				case Failure(err) =>
					log.error(s"Error spawning engine $name", err)

				case _ => ()
			}
		}
	}

	def receive: Receive = {
		case work: EngineMsg.CallEngine =>
			router.route(work, sender)

		case Terminated(actor) =>
			val name = actor.path.name
			log.info(s"EngineHandler $name is terminated.")

			router.removeRoutee(actor)

		case ActorMsg.UpdateProps(props) =>
			log.info(s"Updating with $props")
			props foreach { case (k, v) =>
				propHandleMap get k match {
					case Some(f) => f(v)
					case None =>
						log.warn(s"Update props config key is unhandled: $k")
				}
			}
			spawnRouter()

		case EngineMsg.GetVoices() =>
			sender ! EngineMsg.UpdateVoices(this.voices, Set())

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

/**
 * This class is meant to manage any number of TTS engines.
 *
 * This class routes incoming Tts requests by the desired voice.
 * 	- Default voices are not supported as of this writing.
 *
 * This class supports an IConfiguration of: example:
 * 	engines {
 * 		engine0 = { /* engine configuration */ }
 * 		engine1 = { /* engine configuration */ }
 * 			.....
 * 		engineN = { /* engine configuration */ }
 * 	}
 *
 * This is meant to watch for engine configurations.
 * Due to the nature of this actor, it has no initial config state.
 */
class EnginesProxy(
	configService: IConfigService,
	val configHost: String,
	val configNode: String
		) extends Actor with IConfigurable {

	private[this] val log = Utils.getLogger(this)

	private[this] val track = Utils.getTracker(Constants.trackerKey)

	/**
	 * This maps voice strings to EngineRouter routers.
	 */
	private[this] val voiceMap =
		mutable.Map[String, Router]()

	/**
	 * This is to avoid spawning multiple engine connection managers.
	 */
	private[this] val engineNameSet =
		mutable.Set[String]()

	private[this] val watcher =
		configService(this, Nil)

	watcher match {
		case Failure(err) =>
			log.error("Unable to load watcher.", err)
		case _ => ()
	}

	/**
	 * Attempts to spawn a single EngineRouter as a routee.
	 * @param name The name of the EngineRouter.
	 */
	private[this] def spawnRoutee(name: String) {
		if (engineNameSet contains name) {
			()
		}
		else Try {
			// Spawn the new EngineRouter.
			// The configuration will be loaded eventually.
			log.info(s"Spawning EngineRouter $name")
			val r = context.actorOf(Props(
				classOf[EngineRouter],
				name,
				configService,
				self,
				configHost,
				configNode + s"/$name"
			), name )

			context watch r

			// Inform the new routee that it needs to give us an update of it's voice capabilities.
			r ! EngineMsg.GetVoices()
			// The engine will be added to the route roster once it gives us the reply.

		} match {
			case Success(_) =>
				log.info(s"Spawned EngineRouter $name")
				engineNameSet add name

			case Failure(err) =>
				log.error(s"Unable to spawn EngineRouter $name", err)
		}
	}

	override def receive: Actor.Receive = {
		case msg@EngineMsg.CallEngine(voice, speak) =>
			// Attempt to route the msg to the correct router.
			// Messages are routed by the voice they wish to use.
			// Default voices are not supported at this time.

			voiceMap get voice match {
				case Some(router) =>
					router.route(msg, sender())

				case None =>
					track.put(s"BadVoiceRequest:$voice", 1)

					val err = EngineNotAvailable(
						s"Unable to retrieve $voice engine."
					)
					sender ! CallEngineReply(Failure(err))
			}

		case ActorMsg.UpdateProps(props) =>
			// Each key in these properties should link to an EngineRouter configuration.
			props.foreach { case (k, _) =>
				spawnRoutee(k)
			}

		case EngineMsg.UpdateVoices(voices, oldVoices) =>
			// Here is when we add the engine to the roster.

			// Remove the engine from any old routers, diff the voices first.
			// Only remove the intersection of old and new voices.

			val toRemove = oldVoices -- voices
			val name = sender().path.name

			toRemove foreach { voice =>
				voiceMap get voice match {
					case Some(router) =>
						router.removeRoutee(sender())
					case None =>
						log.error(s"EngineRouter $name was never added to the voice roster $voice.")
				}
			}

			// Add only the new voices.
			val toAdd = voices -- oldVoices

			// Add the engine to the voice roster.
			toAdd foreach { voice =>
				voiceMap get voice match {
					case Some(router) =>
						voiceMap.update(voice, router.addRoutee(sender()))
						log.info(s"Added $name to the voice roster: $voice.")

					case None =>
						// We need to create the router in this case.
						val router = Router(
							SmallestMailboxRoutingLogic(),
							Vector.empty[Routee]
						).addRoutee(sender())

						voiceMap.update(voice, router)
						log.info(s"Created router and added $name to the voice roster: $voice.")
				}
			}

		case Terminated(actor) =>
			val name = actor.path.name
			log.info(s"EngineRouter $name is terminated.")
			engineNameSet remove name
	}

	/**
	 * Callback to inform this object that updates have occurred.
	 * @param props The map of updates that have occurred.
	 */
	override def configUpdate(props: Iterable[(String, String)]) {
		self ! ActorMsg.UpdateProps(props)
	}

	override def postStop() {
		watcher foreach { _.dispose() }
		super.postStop()
	}
}
