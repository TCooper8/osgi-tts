package com.cooper.osgi.speech.service

import akka.actor.{Props, PoisonPill, ActorSystem}
import akka.pattern.ask
import javax.servlet.http.{HttpServletRequest, HttpServletResponse, HttpServlet}
import com.cooper.osgi.speech.ITtsEngine
import com.cooper.osgi.utils.{MaybeLog, StatTracker, Logging}
import scala.concurrent.ExecutionContext
import ExecutionContext.Implicits.global
import com.cooper.osgi.sampled.IAudioReader
import com.cooper.osgi.io.{INode, IOUtils}
import scala.concurrent.Await
import scala.concurrent.duration._
import java.io.{InputStream, ByteArrayInputStream}
import com.cooper.osgi.config.{IConfigurable, IConfigService}
import com.typesafe.config.{ConfigFactory, Config}
import scala.collection.mutable
import scala.util.{Failure, Success, Try}
import java.math.BigInteger
import java.security.MessageDigest
import com.cooper.osgi.speech.service.Constants._
import com.ning.http.client.Response
import akka.util.Timeout

/**
 * Describes an Http servlet that communicates with a StaticTtsEngine <: ITtsEngine
 * @param staticEngine The ITtsEngine to use.
 * @param timeoutSeconds The timeout for each request to the ITtsEngine, in seconds.
 */
class TtsProxyServlet(
		fileSystem: INode,
		configService: IConfigService,
		staticEngine: ITtsEngine,
		synthEngineHost: String,
		encoding: String,
		val configHost: String,
		val configNode: String,
		timeoutSeconds: Int
	) extends HttpServlet with IConfigurable {

	private[this] val log = Logging(this.getClass)

	private[this] val track = StatTracker(Constants.trackerKey)

	private[this] val maybe = MaybeLog(log, track)

	private[this] val timeout = timeoutSeconds seconds
	private[this] implicit val askTimeout = Timeout(timeout)

	private[this] val props = safeMap[String, String](
		"rootPath" -> "/tts/cache",
		"filePrefix" -> "TTS_",
		"fileSuffix" -> ".wav",
		"crypto" 	-> "SHA1",
		"writerInstances" -> "5",
		"urlAlias" -> "/getTTSFile"
	)

	private[this] def filePrefix = props get "filePrefix" get
	private[this] def fileSuffix = props get "fileSuffix" get
	private[this] def rootPath = props get "rootPath" get
	private[this] def writerInstances = props get "writerInstances" get

	private[this] val actorSystem = getActorSystem

	private[this] val fileWriter = actorSystem.actorOf(Props(
		classOf[FileRouter],
		writerInstances.toInt
	))

	private[this] val watcher = configService(
		this,
		props
	).toOption
	if (watcher.isEmpty)
		log.error(s"$this watcher is undefined, problem with ${watcher.getClass}")

	private[this] val engineRouter = actorSystem.actorOf(Props(
		classOf[EngineRouter],
		configService,
		configHost,
		configNode + "/engines"
	))

	private[this] def safeMap[A, B](seq: (A, B)*): mutable.Map[A, B] = {
		val map = new mutable.HashMap[A, B]() with mutable.SynchronizedMap[A, B]
		map ++= seq
	}

	private[this] def getActorSystem = {
		val loader = classOf[ActorSystem].getClassLoader()
		val config: Config = {
			val config = ConfigFactory.defaultReference(loader)
			config.checkValid(ConfigFactory.defaultReference(loader), "akka")
			config
		}
		ActorSystem("Com***REMOVED***SpeechService", config, loader)
	}

	/**
	 * Attempts to copy the audio reader to the http response.
	 * @param resp The response to write to.
	 * @param reader The reader to reader from.
	 */
	private[this] def tryCopyReader(resp: HttpServletResponse, reader: IAudioReader) {
		IOUtils.using( reader ) { reader => maybe {
			IOUtils.using( resp.getOutputStream ) { outStream => maybe {

				resp.setContentType("audio/x-wav")
				resp.setContentLength(reader.getContentLength())
				reader.copyTo(outStream)
				outStream.flush()

				track("Speech:TranslationSuccess", 1)
			}}
		}}
	}

	private[this] def crypto = MessageDigest.getInstance(props get "crypto" get)
	private[this] def hash(s: String) = {
		String.format("%x", new BigInteger(1, crypto.digest(s.getBytes)))
	}

	private[this] def copyToResp(inStream: InputStream, resp: HttpServletResponse) = Try{
		IOUtils.using(inStream) {
			inStream =>
				val outStream = resp.getOutputStream()
				resp.setContentType("audio/x-wav")
				resp.setContentLength(inStream.available)
				IOUtils.copy(inStream, outStream)
				outStream.flush()
		}
	}

	private[this] def callSynthEngine(speak: String, voice: String) = Try{
		import dispatch._

		val uri = s"/getTTSFile?speak=${speak.replace(" ", "%20")}&voice=$voice"
		val longUrl = synthEngineHost + uri
		val service = url(longUrl)
		val task = Http(service)
		task()
	}

	private[this] def cacheResponse(rootNode: INode, data: Array[Byte], key: String) = Try {
		val inStream = new ByteArrayInputStream(data)
		fileWriter ! Constants.WriteFileMsg(rootNode, inStream, key)
	}

	private[this] def pipeResponse(rootNode: INode, key: String, engineResp: Response, resp: HttpServletResponse) = Try{
		engineResp.getStatusCode() match {
			case 200 =>
				val data = engineResp.getResponseBodyAsBytes()
				val contentType = engineResp.getContentType()
				assert(contentType == "audio/x-wav")
				cacheResponse(rootNode, data, key) match {
					case Failure(error) =>
						log.error("Error calling fileWriter.", error)
						resp.sendError(500)
					case _ => ()
				}

				copyToResp(new ByteArrayInputStream(data), resp)
			case n =>
				resp.sendError(n)
		}
	}

	private[this] def handleSynth(speak:String, voice:String, resp:HttpServletResponse) {
		val childPath = s"$rootPath/$voice"
		fileSystem(childPath) match {
			case Left(rootNode) =>
				val key = filePrefix + hash(voice + speak) + fileSuffix

				rootNode.children.get(key) match {
					/*case Some(child) => child.content match {
						case Left(inStream) => copyToResp(inStream, resp)
						case Right(error) =>
							log.error(s"Cannot retrive content from ${child.path}", error)
							resp.sendError(500)
					}*/

					case _ => //callSynthEngine(speak, voice) match {
						Try {
							val task = engineRouter ? CallEngine(voice, speak)

							Await.result(task, timeout) match {
								case CallEngineReply(Success(engineResp)) =>
									pipeResponse(rootNode, key, engineResp, resp) match {
										case Failure(error) =>
											log.error("Unable to pipe responses", error)
											resp.sendError(500)
										case _ => ()
									}
								case CallEngineReply(Failure(error)) =>
									log.error("Unable to call engine", error)
									resp.sendError(500)
								case _ =>
									log.error("Engine response was invalid.")
									resp.sendError(500)
							}
						} match {
							case Failure(err) => log.error(err.getMessage())
							case Success(_) => ()
						}
				}
			case Right(error) =>
				log.error(s"Unable to retrieve $childPath from file system.", error)
				resp.sendError(500)
		}
	}

	private[this] def handleSpell(speak:String, voice:String, resp:HttpServletResponse) = maybe{
		val task = staticEngine.translate(voice)(speak)
		Await.result(task, timeout) match {
			case Some(reader) =>
				tryCopyReader(resp, reader)
				track("Http:Ok", 1)
			case _ =>
				resp.sendError(501)
				track("Http:NotImplemented", 1)
		}
	}

	/**
	 * Called by the server to allow the servlet to handle a GET request.
	 * @param req An HttpServletRequest object that contains the request the client has made of the servlet.
	 * @param resp An HttpServletResponse object that contains the response the servlet sends to the client.
	 */
	override def doGet(req: HttpServletRequest, resp: HttpServletResponse) {
		maybe {
			// Retrieve the voice and phrase from the request, for translation.
			val voiceIn = req.getParameter("voice")
			val phrase = req.getParameter("speak")

			// If the voice or phrase are not defined, send back bad response.
			(voiceIn != null, phrase != null) match {
				case (true, true) =>
					voiceIn.indexOf("_spell") match {
						case -1 => handleSynth(phrase, voiceIn, resp)
						case i => handleSpell(phrase, voiceIn.substring(0, i), resp)
					}

				case _ =>
					track("Http:BadRequests", 1)
					resp.sendError(400)
			}
		}
	}

	/**
	 * Callback to inform this object that updates have occurred.
	 * @param props The map of updates that have occurred.
	 */
	def configUpdate(props: Iterable[(String, String)]) {
		this.props ++= props
		Try { writerInstances.toInt } match {
			case Success(n) =>
				this.fileWriter ! UpdateWriterCount(n)
				log.info(s"Updated file writer instances to $n")
			case Failure(error) => log.error("", error)
		}
	}

	/**
	 * Frees any resources associated with this object.
	 */
	def dispose() {
		actorSystem.actorSelection("*") ! PoisonPill
		watcher foreach { _.dispose }
	}
}
