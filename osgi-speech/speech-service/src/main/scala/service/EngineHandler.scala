package com.cooper.osgi.speech.service

import akka.actor.{PoisonPill, Terminated, Props, Actor}
import com.cooper.osgi.speech.service.Constants._
import scala.util.Try
import akka.routing._
import com.cooper.osgi.config.{IConfigService, IConfigurable}
import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import akka.util.Timeout
import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import akka.io.Udp.SO.Broadcast
import com.cooper.osgi.speech.service.Constants.CallEngineReply
import scala.Some
import com.cooper.osgi.speech.service.Constants.UpdatePropsMap
import akka.routing.Router
import akka.actor.Terminated
import com.cooper.osgi.speech.service.Constants.UpdateProps
import akka.routing.ActorRefRoutee
import com.cooper.osgi.speech.service.Constants.CallEngine

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

	private[this] val kHost = "host"
	private[this] val kAlias = "alias"
	private[this] val kVoices = "voices"
	private[this] val kTimeoutWarning = "timeoutWarning"

	private[this] val props = mutable.Map[String, String](
		kHost -> "localhost:8080",
		kAlias -> "getTTSFile",
		kVoices -> "",
		kTimeoutWarning -> "10"
	)

	private[this] var engineHost = ""
	private[this] var engineAlias = ""
	private[this] var engineVoices = Set[String]()
	private[this] var timeoutWarning = 10

	this.pushProperties()

	private[this] val watcher = configService(
		this,
		props
	).toOption
	if (watcher.isEmpty)
		log.error("Watcher is undefined.")
	
	private[this] def pushProperties() {
		engineHost = {
			val host = props get kHost getOrElse ("")
			if (host.startsWith("http://")) host else s"http://$host"
		}

		engineAlias = {
			val alias = props get kAlias getOrElse ("")
			if (alias.startsWith("/")) alias else s"/$alias"
		}

		engineVoices = parseList( props get kVoices getOrElse("") ).toSet

		timeoutWarning = props.get(kTimeoutWarning).map {
			in => Try{ in.toInt }.getOrElse(10)
		}.getOrElse(10)
	}

	private[this] def parseList(input: String) =
		input.split(",").map {
			s =>
				val ns = s.replaceAll("\\s+", "")
				if (ns == "") None else Some(ns)
		}.flatten

	private[this] def callEngine(voice:String, speak:String) = Try{
		import dispatch._

		val uri = s"$engineHost$engineAlias?voice=$voice&speak=${speak.replace(" ", "%20")}"

		val service = url(uri)
		val task = Http(service)
		val resp = task()

		val rc = resp.getStatusCode()
		if (rc != 200) {
			log.error(s"Engine response error $rc from $name")
			track.put(s"EngineCall($name):Bad", 1l)
		}

		resp
	}

	def receive = {
		case CallEngine(voice: String, speak: String) =>
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

			sender ! CallEngineReply(res)

		case UpdateProps(props) =>
			this.props ++= props
			this.pushProperties()

		case msg =>
			log.error(s"Got invalid message of $msg.")
			track.put(s"BadMsg:$msg", 1l)
	}

	/**
	 * Callback to inform this object that updates have occurred.
	 * @param props The map of updates that have occurred.
	 */
	override def configUpdate(props: Iterable[(String, String)]) {
		log.info(s"Updated $props")
		self ! UpdateProps(props)
	}

	override def postStop() {
		log.info(s"Resetting.")
		watcher foreach { _.dispose() }
		super.postStop()
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

	private[this] val engines =
		mutable.Map[String, String]()

	private[this] val watcher = configService(
		this,
		engines
	).toOption

	private[this] var router: Router =
		Router(SmallestMailboxRoutingLogic(), Vector.empty[Routee])

	private[this] val nameSet =
		mutable.Set[String]()

	private[this] def spawnRoutee(name:String) = {
		context.actorOf(Props(
			classOf[EngineHandler],
			name,
			configService,
			configHost,
			configNode + s"/$name"
		), name)
	}

	private[this] def spawnRouter() {
		engines.foreach {
			case (engineName, _) =>
				log.info(s"Spawning $engineName")
				if (nameSet contains engineName) ()
				else {
					val r = spawnRoutee(engineName)
					context watch r
					router = router.addRoutee(r)
					nameSet add engineName
				}
		}
	}

	def receive = {
		case work: CallEngine =>
			router.route(work, sender())

		case Terminated(actor) =>
			val name = actor.path.name
			nameSet remove name
			router = router.removeRoutee(actor)

			log.info(s"EngineHandler $name is terminated, rerouting work.")

			val r = spawnRoutee(name)
			context watch r
			router = router.addRoutee(r)

		case UpdateProps(props) =>
			this.engines ++= props
			spawnRouter()

		case msg =>
			log.error(s"Got invalid message of $msg.")
			track.put(s"BadMsg:$msg", 1l)
	}

	/**
	 * Callback to inform this object that updates have occurred.
	 * @param props The map of updates that have occurred.
	 */
	def configUpdate(props: Iterable[(String, String)]) {
		self ! UpdateProps(props)
	}

	override def postStop() {
		watcher foreach { _.dispose() }
		super.postStop()
	}
}
