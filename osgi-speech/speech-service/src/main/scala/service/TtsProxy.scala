package com.cooper.osgi.speech.service

import akka.actor.{Props, PoisonPill, ActorSystem}
import akka.pattern.ask
import javax.servlet.http.{HttpServletRequest, HttpServletResponse, HttpServlet}
import com.cooper.osgi.sampled.{IAudioSystem, IAudioReader}
import com.cooper.osgi.io.{ILocalFileSystem, IBucket, IFileSystem}
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
import java.net.InetAddress
import akka.routing.Broadcast

class TtsProxyFront(
		audioSystem: IAudioSystem,
		localFileSystem: ILocalFileSystem,
		configService: IConfigService,
		trackerService: ITrackerService,
		encoding: String,
		val configHost: String,
		configNodeRoot: String
	) extends HttpServlet with IConfigurable {

	Utils.setTrackerService(trackerService)

	private[this] val log =
		Utils.getLogger(this)

	val configNode: String =
		configNodeRoot + getLocalAddress().replace('.', '-')

	private[this] val proxyNode =
		configNode + "/proxy"


	private[this] val kFileSystemType = "fileSystemType"
	private[this] val kS3AccessKey = "s3AccessKey"
	private[this] val kS3SecretKey = "s3SecretKey"
	private[this] val kS3EndPoint = "s3EndPoint"

	private[this] val kS3FileSystemType = "s3"
	private[this] val kLocalFileSystemType = "local"

	private[this] var fileSystemType = "local"
	private[this] var s3AccessKey = ""
	private[this] var s3SecretKey = ""
	private[this] var s3EndPoint = ""

	private[this] val configHandleMap = Map(
		kFileSystemType -> {
			v: String => fileSystemType = v
		},

		kS3AccessKey -> {
			v: String => s3AccessKey = v
		},

		kS3SecretKey -> {
			v: String => s3SecretKey = v
		},

		kS3EndPoint -> {
			v: String => s3EndPoint = v
		}
	)

	private[this] var proxy: Try[TtsProxyServlet] =
		this.createProxy(localFileSystem)

	private[this] val watcher = configService(
		this,
		Nil
	)

	private[this] def getLocalAddress() = {
		InetAddress.getLocalHost().toString().replace("/", "/addr")
	}

	override def doGet(req: HttpServletRequest, resp: HttpServletResponse) {
		val proxyT = this.synchronized {
			this.proxy
		}

		proxyT match {
			case Success(proxy) =>
				proxy.doGet(req, resp)

			case Failure(err) =>
				resp.sendError(503, "Service is temporarily unavailable.")
				log.error(err.getMessage())
		}
	}

	private[this] def createProxy(fileSystem: IFileSystem) = {
		Try {
			new TtsProxyServlet(
				audioSystem,
				fileSystem,
				configService,
				encoding,
				configHost,
				proxyNode
			)
		}
	}

	/**
	 * Callback to inform this object that updates have occurred.
	 * @param props The map of updates that have occurred.
	 */
	def configUpdate(props: Iterable[(String, String)]) {
		log.info(s"Updating with $props")

		this.synchronized {
			props.foreach {
				case (k, v) => configHandleMap.get(k).foreach{ _(v) }
			}
			Try {
				if (fileSystemType == kS3FileSystemType) {

					localFileSystem.getS3(
						s3AccessKey,
						s3SecretKey,
						s3EndPoint
					) match {
						case Success(s3) =>
							createProxy(s3) match {
								case Success(newProxy) =>
									this.proxy foreach { _.dispose() }
									this.proxy = Success(newProxy)
									log.info("Successfully set proxy file system to s3.")

								case Failure(err) =>
									log.error("Unable to update proxy with s3 file system.", err)
							}

						case Failure(err) =>
							log.error(s"Bad credentials: $s3AccessKey, $s3SecretKey, $s3EndPoint")
							log.error("Unable to update proxy with s3 file system.", err)
					}
				}
				else if (fileSystemType == kLocalFileSystemType) {
					log.info("Creating local proxy")

					createProxy(localFileSystem) match {
						case Success(proxy) =>
							this.proxy foreach { _.dispose() }
							this.proxy = Success(proxy)
							log.info(s"Successfully set proxy to local file system")

						case Failure(err) =>
							log.error("Unable to update proxy with local file system.", err)
					}
				}
				else
					log.error(s"Unable to match file system type of $fileSystemType")
			} match {
				case Failure(err) =>
					log.error("", err)
				case _ => ()
			}
		}
	}

	def dispose() {
		watcher foreach { _.dispose() }
		proxy foreach { _.dispose() }
	}

	def configPutFile(rootNode: String, path: String) = Try{
		val file = new java.io.File(path)
		val stream = new java.io.FileInputStream(file)
		watcher foreach { _.putData(rootNode, stream) }
	}
}

case class TtsProxyState(
	var crypto: String,
	var filePrefix: String,
	var fileSuffix: String,
	var httpGetTimeoutDur: FiniteDuration,
	var rootPath: String,
	var bucket: Try[IBucket],
	var writerInstances: Int
)

/**
 * Describes an Http servlet that communicates with a StaticTtsEngine <: ITtsEngine
 */
class TtsProxyServlet(
		audioSystem: IAudioSystem,
		fileSystem: IFileSystem,
		configService: IConfigService,
		encoding: String,
		val configHost: String,
		val configNode: String
	) extends
		HttpServlet with IConfigurable
	{

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

	// This will fail to construct if the bucket is not available.
	private[this] lazy val state = {
		val rootPath = "tts-cache"

		TtsProxyState(
			"SHA1",
			"",
			".wav",
			10 seconds,
			rootPath,
			fileSystem.getBucket(rootPath),
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

				bucket match {
					// Populate the bit field.
					case Success(bucket) =>

						val keys = bucket.listNodes.map {
							_.map {
								_.key
							}
						}.foreach { keys =>
							keys.foreach {
								key:String => fileCachedField.put(key.hashCode())
							}
						}

						// Update the state.
						state.rootPath = path
						state.bucket = Success(bucket)

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
				resp.setStatus(200)
				outStream.flush()
			}
		}
	}

	private[this] def copyToResp(inStream: InputStream, resp: HttpServletResponse) = Try {
		Utils.using(inStream) {
			inStream =>
				val outStream = resp.getOutputStream()
				try {
					resp.setContentType(audioContentType)
					resp.setContentLength(inStream.available)

					Utils.copy(inStream, outStream)
					resp.setStatus(200)
				}
				finally {
					outStream.flush()
					outStream.close()
				}
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
				s"$voice/" +
				  state.filePrefix +
				  hash(speak, state.crypto) +
				  state.fileSuffix

			(state.bucket, fileKey)
		}

		def callEngine(bucket: IBucket): Unit = Try {
			val task = engineRouter ? EngineMsg.CallEngine(voice, speak)

			Await.result(task, timeoutDur) match {
				case EngineMsg.CallEngineReply(Success(engineResp)) =>
					pipeResponse(bucket, fileKey, engineResp, resp) match {
						case Failure(err) =>
							log.error("Unable to pipe responses", err)
							track.put("PipeResponseFailure", 1)
						case _ => ()
					}

				case EngineMsg.CallEngineReply(Failure(err)) =>
					log.error(s"Unable to call engine ${err.getMessage}")
					track.put("CallEngineFailure", 1)

					checkCache(bucket) // Try again until failure

				case _ =>
					log.error("Engine response was invalid.")
					track.put("InvalidEngineResponse", 1)
					track.put("HttpResp:500", 1)
					resp.sendError(500)
			}
		} match {
			case Failure(err) =>
				log.error(err.getMessage)
				track.put(err.getClass.getName, 1)
				track.put("HttpResp:500", 1)

				resp.sendError(500)

			case Success(_) => ()
		}

		def checkCache(bucket: IBucket): Unit = {

			/*val cacheLookup = this.cache.get(fileKey)

			if (cacheLookup.isDefined) {
				track.put(cacheHit, 1)
				val respStream = new ByteArrayInputStream(cacheLookup.get)
				copyToResp(respStream, resp)
			}
			if (fileCachedField.get(fileKey.hashCode()).isDefined) {
				track.put(cacheMiss, 1)

				copyFromFile(bucket, fileKey, resp) match {
					case Success(_) =>
						track.put("FileRead:Success", 1)
						track.put("HttpResp:200", 1)
						track.put(bitFieldHit, 1)

						if (!cache.contains(fileKey)) {
							fileWriter ! FileMsg.PullFileToF(
							bucket, fileKey, {
								(data: Array[Byte]) => cache.put(fileKey, data)
							})
						}

					case Failure(err) =>
						//log.error(s"Error reading file $fileKey", err)
						track.put(bitFieldMiss, 1)
						callEngine(bucket)
				}
			}
			else {
				track.put(cacheMiss, 1)
				callEngine(bucket)
			}*/

			val cacheLookup = this.cache.get(fileKey)

			if (cacheLookup.isDefined) {
				track.put(cacheHit, 1)
				val respStream = new ByteArrayInputStream(cacheLookup.get)
				copyToResp(respStream, resp)
				track.put("HttpResp:200", 1)
			}
			else {
				val inBucket = copyFromFile(bucket, fileKey, resp)
				if (inBucket.isSuccess) {
					track.put("FileRead:Success", 1)
					track.put("HttpResp:200", 1)

					// Recover the file data from here.
					if (!cache.contains(fileKey))
						fileWriter ! FileMsg.PullFileToF(
							bucket,
							fileKey,
							data =>
								if (!cache.contains(fileKey))
									cache.put(fileKey, data)
						)
				}
				else {
					callEngine(bucket)
				}
			}

			/*copyFromFile(bucket, fileKey, resp) match {
				case Success(_) =>
					track.put("FileRead:Success", 1)
					track.put("HttpResp:200", 1)

				case Failure(err) =>
					//log.error(s"Error reading file $fileKey", err)
					callEngine(bucket)
			}*/
		}

		bucket match {
			case Success(bucket) => checkCache(bucket)
			case Failure(err) =>
				log.error(err.getMessage())
		}
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

	case object TtsQuery {
		val kVoice = "voice"
		val kSpeak = "speak"

		def unapply(req: HttpServletRequest): Option[(String, String)] =
			(Option{req.getParameter(kVoice)}, Option{req.getParameter(kSpeak)}) match {
				case (Some(voice), Some(speak)) => Some((voice, speak))
				case _ => None
			}
	}

	/**
	 * Called by the server to allow the servlet to handle a GET request.
	 * @param req An HttpServletRequest object that contains the request the client has made of the servlet.
	 * @param resp An HttpServletResponse object that contains the response the servlet sends to the client.
	 */
	override def doGet(req: HttpServletRequest, resp: HttpServletResponse) {
		Try {
			req match {
				case TtsQuery(voice, phrase) =>
					// Set timeout implicits
					implicit val timeoutDur = sync {
						state.httpGetTimeoutDur
					}
					implicit val timeout = Timeout(timeoutDur)

					voice.indexOf("_spell") match {
						case -1 => handleSynth(phrase, voice, resp)
						case i => handleSpell(phrase, voice.substring(0, i), resp)
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
	 * Frees any resources associated with this object.
	 */
	def dispose() {
		actorSystem.actorSelection("*") ! PoisonPill
		watcher foreach { _.dispose }
		staticEngine.dispose()
	}
}
