package com.cooper.osgi.speech.service

import akka.actor.{Props, PoisonPill, ActorSystem}
import akka.pattern.ask
import javax.servlet.http.{HttpServletRequest, HttpServletResponse, HttpServlet}
import com.cooper.osgi.sampled.{IAudioSystem, IAudioReader}
import com.cooper.osgi.io.IFileSystem
import scala.concurrent.Await
import scala.concurrent.duration._
import java.io.InputStream
import com.cooper.osgi.config.{IConfigurable, IConfigService}
import com.typesafe.config.{ConfigFactory, Config}
import scala.util.{Failure, Success, Try}
import java.math.BigInteger
import java.security.MessageDigest
import com.cooper.osgi.speech.service.Constants._
import com.ning.http.client.Response
import akka.util.Timeout
import com.cooper.osgi.tracking.ITrackerService
import java.util.concurrent.atomic.AtomicReference


/**
 * Describes an Http servlet that communicates with a StaticTtsEngine <: ITtsEngine
 */
class TtsProxyServlet(
		audioSystem: IAudioSystem,
		fileSystem: IFileSystem,
		configService: IConfigService,
		trackerService: ITrackerService,
		encoding: String,
		val configHost: String,
		configNodeRoot: String
	) extends
		HttpServlet with IConfigurable
	{

	Utils.setTrackerService(trackerService)

	val configNode =
		configNodeRoot + "/proxy"

	private[this] val staticEngine = new StaticTtsEngine(
		audioSystem,
		fileSystem,
		configService,
		encoding,
		configHost,
		configNode + "/staticEngine"
	)

	private[this] val log =
		Utils.getLogger(this)

	val track =
		Utils.getTracker(Constants.trackerKey)

	private[this] def maybe[A](expr: => A): Option[A] = maybe("")(expr)
	private[this] def maybe[A](msg: String = "")(expr: => A): Option[A] = {
		Try{ expr } match {
			case Success(m) => Option(m)
			case Failure(err) =>
				log.error(msg, err)
				track.put(msg.getClass.getName, 1)
				None
		}
	}

	/**
	 * Configuration keys
	 */

	private[this] val kCrypto = "crypto"
	private[this] val kFilePrefix = "filePrefix"
	private[this] val kFileSuffix = "fileSuffix"
	private[this] val kHttpGetTimeout = "httpGetTimeout"
	private[this] val kRootPath = "rootPath"
	private[this] val kUrlAlias = "urlAlias"
	private[this] val kWriterInstances = "writerInstances"

	/**
	 * Configuration values
	 */

	private[this] val crypto =
		new AtomicReference(
			MessageDigest.getInstance("SHA1")
		)

	private[this] val filePrefix =
		new AtomicReference("TTS_")

	private[this] val fileSuffix =
		new AtomicReference(".wav")

	private[this] val rootPath =
		new AtomicReference(
			System.getProperty("user.dir") + "/tts/cache"
		)

	private[this] val writerInstances =
		new AtomicReference(5)

	private[this] val timeoutDur =
		new AtomicReference(10 seconds)

	private[this] val timeout =
		new AtomicReference(Timeout(timeoutDur.get))

	/**
	 * This maps configuration keys to functionality within this class.
	 */
	private[this] val propHandleMap = Map(
		kRootPath -> { rootPath.set(_) },

		kFilePrefix -> { filePrefix.set(_) },

		kFileSuffix -> { fileSuffix.set(_) },

		kCrypto -> { v:String =>
			Try{ MessageDigest.getInstance(v) }
			  .foreach{ crypto.set(_) }
		},

		kWriterInstances -> { v:String =>
			Try{ v.toInt }
			  .foreach{ writerInstances.set(_) }
		},

		kHttpGetTimeout -> { v: String =>
			Try{ v.toInt }
			  .map{ _.seconds }
			  .foreach{ v =>
				timeoutDur.set(v)
				timeout.set(Timeout(v))
			}
		}
	)

	private[this] implicit def askTimeout =
		timeout.get()

	private[this] val actorSystem =
		getActorSystem

	private[this] val fileWriter = actorSystem.actorOf(Props(
		classOf[FileRouter],
		writerInstances.get()
	))

	private[this] val watcher = configService(
		this,
		Iterable(
			kCrypto -> crypto.get.getAlgorithm(),
			kFilePrefix -> filePrefix.get,
			kFileSuffix -> fileSuffix.get,
			kHttpGetTimeout -> timeoutDur.get().toSeconds.toString,
			kRootPath -> rootPath.get,
			kWriterInstances -> writerInstances.get.toString
		)
	).toOption
	if (watcher.isEmpty)
		log.error(s"$this watcher is undefined, problem with ${watcher.getClass}")

	private[this] val engineRouter = actorSystem.actorOf(Props(
		classOf[EngineRouter],
		configService,
		configHost,
		configNode + "/engines"
	))

	/**
	 * Callback to inform this object that updates have occurred.
	 * @param props The map of updates that have occurred.
	 */
	def configUpdate(props: Iterable[(String, String)]) {
		props.foreach {
			case (k, v) =>
				propHandleMap.get(k).foreach( _.apply(v) )
		}
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

	private[this] val speechSuccess = "StaticTranslation:Good"
	private[this] val speechFailure = "StaticTranslation:Bad"

	private[this] val audioContentType = "audio/x-wav"

	private[this] def hash(s: String) = {
		String.format("%x", new BigInteger(1, crypto.get.digest(s.getBytes)))
	}

	/**
	 * Attempts to copy the audio reader to the http response.
	 * @param resp The response to write to.
	 * @param reader The reader to reader from.
	 */
	private[this] def tryCopyReader(resp: HttpServletResponse, reader: IAudioReader) = Try{
		Utils.using( reader ) { reader =>
			Utils.using( resp.getOutputStream ) { outStream =>

				resp.setContentType("audio/x-wav")
				resp.setContentLength(reader.getContentLength())
				reader.copyTo(outStream)
				outStream.flush()
			}
		}
	}

	private[this] def copyToResp(inStream: InputStream, resp: HttpServletResponse) = Try {
		Utils.using(inStream) {
			inStream =>
				val outStream = resp.getOutputStream()
				resp.setContentType(audioContentType)
				resp.setContentLength(inStream.available)
				Utils.copy(inStream, outStream)
				outStream.flush()
		}
	}

	private[this] def pipeResponse(
			bucketPath: String,
			key: String,
			engineResp: Response,
			resp: HttpServletResponse
				) = Try {
		engineResp.getStatusCode() match {
			case 200 =>
				// Ensure the content type is correct.
				assert(engineResp.getContentType() == audioContentType)

				// Pulling two streams, one for caching, the other to copy straight to the response.
				val inStreamA = engineResp.getResponseBodyAsStream()
				val inStreamB = engineResp.getResponseBodyAsStream()

				copyToResp(inStreamB, resp)

				fileSystem.createBucket(bucketPath) match {
					case Success(bucket) =>
						fileWriter ! WriteFileMsg(bucket, inStreamA, key)

					case Failure(_) =>
				}

				track.put("HttpResp:200", 1)

			case n =>
				resp.sendError(n)
				track.put(s"HttpResp:$n", 1)
		}
	}

	private[this] def copyFromFile(bucketPath: String, key: String, resp: HttpServletResponse) = {
		fileSystem.getBucket(bucketPath)
		  .flatMap{ _.read(key) }
		  .flatMap{ _.content }
		  .flatMap{ content => copyToResp(content, resp) }
	}

	private[this] def handleSynth(speak: String, voice: String, resp: HttpServletResponse) = Try {
		val bucketPath = s"$rootPath/$voice"
		val fileKey = filePrefix + hash(speak) + fileSuffix

		def callEngine() = Try {
			val task = engineRouter ? CallEngine(voice, speak)

			Await.result(task, timeoutDur.get) match {
				case CallEngineReply(Success(engineResp)) =>
					pipeResponse(bucketPath, fileKey, engineResp, resp) match {
						case Failure(err) =>
							log.error("Unable to pipe responses", err)
							track.put("PipeResponseFailure", 1)
							resp.sendError(500)
						case _ =>
							track.put("HttpResp:200", 1)
					}

				case CallEngineReply(Failure(err)) =>
					log.error("Unable to call engine", err)
					track.put("CallEngineFailure", 1)
					track.put("HttpResp:500", 1)
					resp.sendError(500)

				case _ =>
					log.error("Engine response was invalid.")
					track.put("InvalidEngineResponse", 1)
					track.put("HttpResp:500", 1)
					resp.sendError(500)
			}
		} match {
			case Failure(err) =>
				log.error("", err)
				track.put(err.getClass.getName, 1)

			case Success(_) => ()
		}

		copyFromFile(bucketPath, fileKey, resp) match {
			case Success(_) =>
				track.put("FileRead:Success", 1)
				track.put("HttpResp:200", 1)

			case Failure(_) =>
				callEngine()
		}
	}

	private[this] def handleSpell(speak: String, voice: String, resp: HttpServletResponse) = {
		Try {
			val task = staticEngine.translate(voice)(speak)
			Await.result(task, timeoutDur.get) match {
				case Success(reader) =>
					tryCopyReader(resp, reader)
					track.put("HttpResp:200", 1)

				case Failure(err) =>
					resp.sendError(501)
					track.put(err.getClass.getName, 1)
					track.put("HttpResp:501", 1)
			}
		} match {
			case Success(_) =>
				track.put(speechSuccess, 1)
			case Failure(err) =>
				track.put(speechFailure, 1)
				track.put(err.getClass.getName, 1)
				log.error(s"Unable to translate phrase $speak", err)
		}
	}

	/**
	 * Called by the server to allow the servlet to handle a GET request.
	 * @param req An HttpServletRequest object that contains the request the client has made of the servlet.
	 * @param resp An HttpServletResponse object that contains the response the servlet sends to the client.
	 */
	override def doGet(req: HttpServletRequest, resp: HttpServletResponse) {
		Try {
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
					track.put("Http:BadRequest", 1)
					resp.sendError(400)
			}
		} match {
			case Failure(err) =>
				log.error("", err)
				track.put(err.getClass.getName, 1)

			case _ =>
				()
		}
	}

	def configPutFile(path: String) = {
		fileSystem.getNode("", path).map { node =>
			node.content map { inStream =>
				watcher foreach { _.putData(inStream) }
			}
		}.flatten
	}

	/**
	 * Frees any resources associated with this object.
	 */
	def dispose() {
		actorSystem.actorSelection("*") ! PoisonPill
		watcher foreach { _.dispose }
		staticEngine.dispose()
	}
}

/*
/**
 * Describes an Http servlet that communicates with a StaticTtsEngine <: ITtsEngine
 */
class TtsProxyServlet(
		audioSystem: IAudioSystem,
		fileSystem: IFileSystem,
		configService: IConfigService,
		trackerService: ITrackerService,
		encoding: String,
		val configHost: String,
		configNodeRoot: String
	) extends
		HttpServlet {

	Utils.setTrackerService(trackerService)

	private[this] val log = Utils.getLogger(this)

	private[this] val track = Utils.getTracker(Constants.trackerKey)

	private[this] val actorSystem = getActorSystem

	private[this] val actor = actorSystem.actorOf(Props(
		classOf[ProxyRouter],
		audioSystem,
		fileSystem,
		configService,
		encoding,
		configHost,
		configNodeRoot
	))

	private[this] implicit val timeout = Timeout(60 seconds)

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
	 * Called by the server to allow the servlet to handle a GET request.
	 * @param req An HttpServletRequest object that contains the request the client has made of the servlet.
	 * @param resp An HttpServletResponse object that contains the response the servlet sends to the client.
	 */
	override def doGet(req: HttpServletRequest, resp: HttpServletResponse) {
		Try {
			// Retrieve the voice and phrase from the request, for translation.
			val voiceIn = req.getParameter("voice")
			val phrase = req.getParameter("speak")

			// If the voice or phrase are not defined, send back bad response.
			(voiceIn != null, phrase != null) match {
				case (true, true) =>
					val task = actor ? CallProxy(voiceIn, phrase, resp)

					Await.result(task, 60 seconds) match {
						case CallProxyReply(res) => res match {
							case Success(_) => ()
							case Failure(err) =>
								log.error("", err)
								track.put(err.getClass.getName, 1)
						}
					}

				case _ =>
					track.put("Http:BadRequest", 1)
					resp.sendError(400)
			}
		} match {
			case Failure(err) =>
				log.error("", err)
				track.put(err.getClass.getName, 1)

			case _ =>
				()
		}
	}

	def configPutFile(path: String) = Try {
		val task = actor ? ConfigPutFile(path)
		Await.result(task, 30 seconds).asInstanceOf[Try[Unit]]
	}.flatten
}*/

/*

/**
 * Describes an Http servlet that communicates with a StaticTtsEngine <: ITtsEngine
 */
class TtsProxyServlet(
		audioSystem: IAudioSystem,
		fileSystem: IFileSystem,
		configService: IConfigService,
		trackerService: ITrackerService,
		encoding: String,
		val configHost: String,
		configNodeRoot: String
	) extends
		HttpServlet with IConfigurable
	{

	private[this] val staticEngine = new StaticTtsEngine(
		audioSystem,
		fileSystem,
		configService,
		encoding,
		configHost,
		configNodeRoot + "/voices"
	)

	private[this] val log =
		Utils.getLogger(this)

	val track =
		Utils.getTracker(Constants.trackerKey)

	private[this] def maybe[A](expr: => A): Option[A] = maybe("")(expr)
	private[this] def maybe[A](msg: String = "")(expr: => A): Option[A] = {
		Try{ expr } match {
			case Success(m) => Option(m)
			case Failure(err) =>
				log.error(msg, err)
				None
		}
	}

	private[this] val kRootPath = "rootPath"
	private[this] val kFilePrefix = "filePrefix"
	private[this] val kFileSuffix = "fileSuffix"
	private[this] val kCrypto = "crypto"
	private[this] val kWriterInstances = "writerInstances"
	private[this] val kUrlAlias = "urlAlias"
	private[this] val kHttpGetTimeout = "httpGetTimeout"

	private[this] val props: mutable.Map[String, String] = safeMap[String, String](
		kRootPath -> (System.getProperty("user.dir") + "/tts/cache"),
		kFilePrefix -> "TTS_",
		kFileSuffix -> ".wav",
		kCrypto 	-> "SHA1",
		kWriterInstances -> "5",
		kUrlAlias -> "/getTTSFile",
		kHttpGetTimeout -> "10"
	)

	private[this] def filePrefix = props.get(kFilePrefix).get
	private[this] def fileSuffix = props.get(kFileSuffix).get
	private[this] def rootPath = props.get(kRootPath).get
	private[this] def writerInstances = props.get(kWriterInstances).get
	private[this] def timeout: FiniteDuration = props.get(kHttpGetTimeout).fold(10 seconds)(in => Try {
		in.toInt.seconds
	}.getOrElse(10 seconds))

	private[this] implicit def askTimeout = Timeout(timeout)

	private[this] val actorSystem = getActorSystem

	private[this] val fileWriter = actorSystem.actorOf(Props(
		classOf[FileRouter],
		writerInstances.toInt
	))

	val configNode = configNodeRoot + "/proxy"

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

	val speechSuccess = "StaticTranslation:Good"

	/**
	 * Attempts to copy the audio reader to the http response.
	 * @param resp The response to write to.
	 * @param reader The reader to reader from.
	 */
	private[this] def tryCopyReader(resp: HttpServletResponse, reader: IAudioReader) {
		Utils.using( reader ) { reader => Try {
			Utils.using( resp.getOutputStream ) { outStream => maybe {

				resp.setContentType("audio/x-wav")
				resp.setContentLength(reader.getContentLength())
				reader.copyTo(outStream)
				outStream.flush()

				track.put(speechSuccess, 1l)
			}}
		}}
	}

	private[this] def crypto = MessageDigest.getInstance(props get kCrypto get)
	private[this] def hash(s: String) = {
		String.format("%x", new BigInteger(1, crypto.digest(s.getBytes)))
	}

	private[this] def copyToResp(inStream: InputStream, resp: HttpServletResponse) = Try{
		Utils.using(inStream) {
			inStream =>
				val outStream = resp.getOutputStream()
				resp.setContentType("audio/x-wav")
				resp.setContentLength(inStream.available)
				Utils.copy(inStream, outStream)
				outStream.flush()
		}
	}

	private[this] def cacheResponse(rootNode: IBucket, data: Array[Byte], key: String) = Try {
		val inStream = new ByteArrayInputStream(data)
		fileWriter ! Constants.WriteFileMsg(rootNode, inStream, key)
	}

	private[this] def pipeResponse(rootNode: IBucket, key: String, engineResp: Response, resp: HttpServletResponse) = Try{
		engineResp.getStatusCode() match {
			case 200 =>
				val data = engineResp.getResponseBodyAsBytes()
				val contentType = engineResp.getContentType()
				assert(contentType == "audio/x-wav")

				/**
				 * This is where the data is cached.
				 */
				cacheResponse(rootNode, data, key) match {
					case Failure(err) =>
						log.error("Error calling fileWriter.", err)
						track.put(err.getClass.getName, 1)
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
		fileSystem.createBucket(childPath) match {
			case Success(bucket) =>
				val key = filePrefix + hash(voice + speak) + fileSuffix

				bucket.read(key).flatMap{ _.content } match {
					case Success(content) => copyToResp(content, resp) match {
						case Success(_) =>
							track.put("FileRead:Success", 1)
							track.put("Http:Ok", 1)

						case Failure(err) =>
							log.error(s"Can't read file $key", err)
							track.put("FileRead:Failure", 1)
					}

					//case _ =>
					case Failure(_) =>
						Try {
							val task = engineRouter ? CallEngine(voice, speak)

							Await.result(task, timeout) match {
								case CallEngineReply(Success(engineResp)) =>
									pipeResponse(bucket, key, engineResp, resp) match {
										case Failure(error) =>
											log.error("Unable to pipe responses", error)
											track.put("PipeResponseFailure", 1)
											resp.sendError(500)

										case _ =>
											track.put("Http:Ok", 1)
									}

								case CallEngineReply(Failure(error)) =>
									log.error("Unable to call engine", error)
									track.put("CallEngineFailure", 1)
									resp.sendError(500)

								case _ =>
									log.error("Engine response was invalid.")
									track.put("InvalidEngineResponse", 1)
									resp.sendError(500)
							}
						} match {
							case Failure(err) =>
								log.error(err.getMessage())
								track.put(err.getClass.getName, 1)

							case Success(_) => ()
						}
				}
			case Failure(err) =>
				log.error(s"Unable to retrieve $childPath from file system.", err)
				track.put(err.getClass.getName, 1)
				resp.sendError(500)
		}
	}

	private[this] def handleSpell(speak:String, voice:String, resp:HttpServletResponse) = maybe{
		val task = staticEngine.translate(voice)(speak)
		Await.result(task, timeout) match {
			case Success(reader) =>
				tryCopyReader(resp, reader)
				track.put("Http:Ok", 1)

			case Failure(e) =>
				resp.sendError(501)
				track.put(e.getClass.getName, 1)
		}
	}

	/**
	 * Called by the server to allow the servlet to handle a GET request.
	 * @param req An HttpServletRequest object that contains the request the client has made of the servlet.
	 * @param resp An HttpServletResponse object that contains the response the servlet sends to the client.
	 */
	override def doGet(req: HttpServletRequest, resp: HttpServletResponse) {
		Try {
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
					track.put("Http:BadRequest", 1)
					resp.sendError(400)
			}
		} match {
			case Failure(err) =>
				log.error("", err)
				track.put(err.getClass.getName, 1)

			case _ =>
				()
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
			case Failure(error) => log.error("", error)
		}
	}

	def configPutFile(path: String) = {
		fileSystem.getNode("", path).map { node =>
			node.content map { inStream =>
				watcher foreach { _.putData(inStream) }
			}
		}.flatten
	}

	/**
	 * Frees any resources associated with this object.
	 */
	def dispose() {
		actorSystem.actorSelection("*") ! PoisonPill
		watcher foreach { _.dispose }
		staticEngine.dispose()
	}
}*/
