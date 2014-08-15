package com.cooper.osgi.speech.service

import akka.actor.{Props, PoisonPill, ActorSystem}
import akka.pattern.ask
import javax.servlet.http.{HttpServletRequest, HttpServletResponse, HttpServlet}
import com.cooper.osgi.sampled.{IAudioSystem, IAudioReader}
import com.cooper.osgi.io.{IBucket, IFileSystem}
import scala.concurrent.Await
import scala.concurrent.duration._
import java.io.{ByteArrayInputStream, InputStream}
import com.cooper.osgi.config.{IConfigurable, IConfigService}
import com.typesafe.config.{ConfigFactory, Config}
import scala.util.{Failure, Success, Try}
import java.math.BigInteger
import java.security.MessageDigest
import com.ning.http.client.Response
import akka.util.Timeout
import com.cooper.osgi.tracking.ITrackerService

case class TtsProxyState(
		var crypto:String,
		var filePrefix:String,
		var fileSuffix:String,
		var httpGetTimeoutDur: FiniteDuration,
		var rootPath:String,
		var bucket:IBucket,
		var writerInstances:Int
	)

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

	private[this] val track =
		Utils.getTracker(Constants.trackerKey)

	private[this] val fileCachedField =
		new SynchronizedBitField(1 << 20)

	private[this] val cache =
		new SynchronizedLruMap[String, Array[Byte]](1024)

	/**
	 * Configuration keys
	 */

	private[this] val kCrypto = "crypto"
	private[this] val kFilePrefix = "filePrefix"
	private[this] val kFileSuffix = "fileSuffix"
	private[this] val kHttpGetTimeout = "httpGetTimeout"
	private[this] val kRootPath = "rootPath"
	private[this] val kWriterInstances = "writerInstances"

	/**
	 * Configuration state
	 */

	private[this] val state = {
		val rootPath =
			System.getProperty("user.dir") +
			"/tts/cache"

		TtsProxyState(
			"SHA1",
			"",
			".wav",
			10 seconds,
			rootPath,
			fileSystem.getBucket(rootPath)
			  .orElse(fileSystem.createBucket(rootPath)).get,
			5
		)
	}

	/**
	 * This maps configuration keys to functionality within this class.
	 * - Any time this is used, the state should be synchronized.
	 */
	private[this] val propHandleMap = Map(
		kRootPath -> { path:String =>
		  	if (state.rootPath != path) {

				// Clear the bit field.
				// - The root file has changed.
				fileCachedField.clear()

				// Retrieve or create the new bucket.
				val bucket =
					fileSystem.getBucket(path)
					  .orElse(fileSystem.createBucket(path))

				bucket match {
					// Populate the bit field.
					case Success(bucket) =>

						// Update the state.
						state.rootPath = path
						state.bucket = bucket

					case Failure(err) =>
						log.error("Unable to update bucket.", err)
				}
			}
		},

		kFilePrefix -> { v:String => state.filePrefix = v },

		kFileSuffix -> { v:String => state.fileSuffix = v },

		kCrypto -> { v:String => state.crypto = v },

		kWriterInstances -> { v:String =>
			Try{ v.toInt }
			  .foreach{ state.writerInstances = _ }
		},

		kHttpGetTimeout -> { v: String =>
			Try{ v.toInt }
			  .map{ _.seconds }
			  .foreach{ v =>
			  	state.httpGetTimeoutDur = v
			}
		}
	)

	private[this] val actorSystem =
		getActorSystem

	private[this] val fileWriter = actorSystem.actorOf(Props(
		classOf[FileRouter],
		state.writerInstances,
		fileCachedField
	))

	private[this] val watcher = configService(
		this,
		Iterable(
			kCrypto -> state.crypto,
			kFilePrefix -> state.filePrefix,
			kFileSuffix -> state.fileSuffix,
			kHttpGetTimeout -> state.httpGetTimeoutDur.toSeconds.toString,
			kRootPath -> state.rootPath,
			kWriterInstances -> state.writerInstances.toString
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

	private[this] def sync[A](m: A) = state.synchronized(m)

	/**
	 * Callback to inform this object that updates have occurred.
	 * @param props The map of updates that have occurred.
	 */
	def configUpdate(props: Iterable[(String, String)]) {
		log.info(s"Updating with $props")
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
	private[this] val cacheHit = "Cache:Hit"
	private[this] val cacheMiss = "Cache:Miss"
	private[this] val bitFieldHit = "BitField:Miss"
	private[this] val bitFieldMiss = "BitField:Miss"

	private[this] val audioContentType = "audio/x-wav"

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
			bucket: IBucket,
			key: String,
			engineResp: Response,
			resp: HttpServletResponse
				) = Try {
		engineResp.getStatusCode() match {
			case 200 =>
				// Ensure the content type is correct.
				assert(engineResp.getContentType() == audioContentType,
					s"Engine response content type was invalid : ${engineResp.getContentType}")

				// Pull the byte data from the response and cache it.
				val byteData = engineResp.getResponseBodyAsBytes()
				val _ = this.cache.put(key, byteData)

				// Create two streams for file writing and the response.
				val fileStream = new ByteArrayInputStream(byteData)
				val respStream = new ByteArrayInputStream(byteData)

				// Pulling two streams, one for caching, the other to copy straight to the response.
				//val inStreamA = engineResp.getResponseBodyAsStream()
				//val inStreamB = engineResp.getResponseBodyAsStream()

				copyToResp(respStream, resp)

				fileWriter ! FileMsg.WriteFileMsg(bucket, fileStream, key)

				track.put("HttpResp:200", 1)

			case n =>
				resp.sendError(n)
				track.put(s"HttpResp:$n", 1)
		}
	}

	private[this] def copyFromFile(bucket: IBucket, key: String, resp: HttpServletResponse) = {
		bucket.read(key)
		  .flatMap{ _.content }
		  .flatMap{ content =>
		  		// Recover the cache if it does not contain the key.
		  		if (!cache.contains(key))
					fileWriter ! FileMsg.PullFileToF(
						bucket, key,
						{ (data: Array[Byte]) => cache.put(key, data) }
					)
				copyToResp(content, resp)
		}
	}
	def hash(s: String, crypto:String) = {
		val cryptoInstance = MessageDigest.getInstance(crypto)
		String.format("%x", new BigInteger(1, cryptoInstance.digest(s.getBytes)))
	}

	private[this] def handleSynth
		(speak: String, voice: String, resp: HttpServletResponse)
		(implicit timeoutDur: FiniteDuration, timeout: Timeout) = Try {

		val (bucket, fileKey) = sync{
			val fileKey =
				s"/$voice/" +
				  state.filePrefix +
				  hash(speak, state.crypto) +
				  state.fileSuffix

			(state.bucket, fileKey)
		}

		def callEngine(): Unit = Try {
			val task = engineRouter ? EngineMsg.CallEngine(voice, speak)

			Await.result(task, timeoutDur) match {
				case EngineMsg.CallEngineReply(Success(engineResp)) =>
					pipeResponse(bucket, fileKey, engineResp, resp) match {
						case Failure(err) =>
							log.error("Unable to pipe responses", err)
							track.put("PipeResponseFailure", 1)
							resp.sendError(500)
						case _ =>
							track.put("HttpResp:200", 1)
					}

				case EngineMsg.CallEngineReply(Failure(err)) =>
					log.error(s"Unable to call engine ${err.getMessage}")
					track.put("CallEngineFailure", 1)

					checkCache() // Try again until failure

				case _ =>
					log.error("Engine response was invalid.")
					track.put("InvalidEngineResponse", 1)
					track.put("HttpResp:500", 1)
					resp.sendError(500)
			}
		} match {
			case Failure(err) =>
				log.error(err.getMessage())
				track.put(err.getClass.getName, 1)
				track.put("HttpResp:500", 1)

				resp.sendError(500)

			case Success(_) => ()
		}

		def checkCache(): Unit = {

			val cacheLookup = this.cache.get(fileKey)

			if (cacheLookup.isDefined) {
				track.put(cacheHit, 1)
				val respStream = new ByteArrayInputStream(cacheLookup.get)
				copyToResp(respStream, resp)
			}
			else if (fileCachedField.get(fileKey.hashCode()).isDefined) {
				track.put(cacheMiss, 1)

				copyFromFile(bucket, fileKey, resp) match {
					case Success(_) =>
						track.put("FileRead:Success", 1)
						track.put("HttpResp:200", 1)
						fileCachedField.put(fileKey.hashCode())

					case Failure(_) =>
						track.put(bitFieldMiss, 1)
						callEngine()
				}
			}
			else {
				track.put(cacheMiss, 1)
				callEngine()
				track.put(bitFieldHit, 1)
			}
		}

		checkCache()
	}

	private[this] def handleSpell
		(speak: String, voice: String, resp: HttpServletResponse)
		(implicit timeoutDur: FiniteDuration, timeout: Timeout) = {
		Try {
			val task = staticEngine.translate(voice)(speak)
			Await.result(task, timeoutDur) match {
				case Success(reader) =>
					if (reader.chainLength == 0 && speak.replaceAll("/s*", "").length > 0) {
						resp.sendError(500, "Unable to translate request.")
						track.put("HttpResp:500", 1)
						track.put(speechFailure, 1)
						log.error(
							s"""
							  |Encountered an error in static translation.
							  |Unable to translate request of $speak, $voice."
							""".stripMargin)
					} else {
						tryCopyReader(resp, reader)
						track.put("HttpResp:200", 1)
						track.put(speechSuccess, 1)
					}

				case Failure(err) =>
					resp.sendError(501)
					log.error("", err)
					track.put(err.getClass.getName, 1)
					track.put("HttpResp:501", 1)
			}
		} match {
			case Success(_) => ()
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
					// Set timeout implicits
					implicit val timeoutDur = sync {
						state.httpGetTimeoutDur
					}
					implicit val timeout = Timeout(timeoutDur)

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
