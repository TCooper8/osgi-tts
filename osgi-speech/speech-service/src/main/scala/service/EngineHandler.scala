package com.cooper.osgi.speech.service

import akka.actor.{PoisonPill, Terminated, Props, Actor}
import com.cooper.osgi.speech.service.Constants._
import scala.util.Try
import com.cooper.osgi.utils.Logging
import akka.routing.{Routee, SmallestMailboxRoutingLogic, Router, ActorRefRoutee}
import com.cooper.osgi.config.{IConfigService, IConfigurable}
import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global

class EngineHandler(
		name: String,
		configService: IConfigService,
		val configHost: String,
		val configNode: String
	) extends Actor with IConfigurable {

	private[this] val log = Logging(name)

	private[this] val kHost = "host"
	private[this] val kAlias = "alias"
	private[this] val kVoices = "voices"
	private[this] val kInstances = "instances"

	private[this] val props = mutable.Map[String, String](
		kHost -> "localhost:8080",
		kAlias -> "getTTSFile",
		kVoices -> "",
		kInstances -> "4"
	)

	private[this] var engineHost = ""
	private[this] var engineAlias = ""
	private[this] var engineVoices = Set[String]()
	private[this] var instances = 2

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

		instances = props get kInstances map { _.toInt } getOrElse(2)
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
		task()
	}

	def receive = {
		case CallEngine(voice: String, speak: String) =>
			sender ! CallEngineReply(callEngine(voice, speak))

		case UpdateProps(props) =>
			this.props ++= props
			this.pushProperties()

		case msg => log.error(s"Got invalid message of $msg.")
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

	private[this] val log = Logging(this.getClass)

	private[this] val engines = mutable.Map[String, String]()

	private[this] val watcher = configService(
		this,
		engines
	).toOption

	private[this] var router: Router = Router(SmallestMailboxRoutingLogic(), Vector.empty[Routee])
	private[this] val nameSet = mutable.Set[String]()

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
				println(engineName)
				if (nameSet contains engineName) ()
				else {
					val r = spawnRoutee(engineName)
					context watch r
					router = router.addRoutee(r)
					nameSet add engineName

				}
				//context watch r
				//ActorRefRoutee(r)
		}

		//Router(SmallestMailboxRoutingLogic(), routees)
	}

	def receive = {
		case work: CallEngine =>
			router.route(work, sender())

		case Terminated(actor) =>
			val name = actor.path.name
			nameSet remove name
			router = router.removeRoutee(actor)
			val r = spawnRoutee(name)
			context watch r
			router = router.addRoutee(r)

			/*val r = context.actorOf(Props[FileWriter])
			context watch r
			router = router.addRoutee(r)*/

		case UpdateProps(props) =>
			this.engines ++= props
			spawnRouter()

		case msg => log.error(s"Got invalid message of $msg.")
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
