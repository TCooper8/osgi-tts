package com.cooper.osgi.speech.service

import akka.actor.{Props, PoisonPill, ActorSystem}
import akka.pattern.ask
import javax.servlet.http.{HttpServletRequest, HttpServletResponse, HttpServlet}
import com.cooper.osgi.sampled.{IAudioSystem, IAudioReader}
import com.cooper.osgi.io.{ILocalFileSystem, IBucket}
import scala.concurrent.{duration, Await}
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
import java.util.concurrent.TimeoutException
import scala.collection.mutable


/**
 * This acts as the mutable state of the Proxy.
 * 	- Access to this must be synchronized.
 * @param crypto The crypto instance to pull.
 * @param filePrefix The file prefix to use for retrieving and caching files.
 * @param fileSuffix The file suffix to use for retrieving and caching files.
 * @param httpGetTimeoutDur The timeout to use when an HTTP GET request is invoked.
 * @param rootPath The root path associated IBucket to attempt to pull from the file system.
 * @param bucket The attempted open for the associated file system bucket.
 * @param writerInstances The number of writer instances to use.
 */
case class TtsProxyState(
	var crypto: String,
	var filePrefix: String,
	var fileSuffix: String,
	var httpGetTimeoutDur: FiniteDuration,
	var rootPath: String,
	var fileSystemType: String,
	var bucket: Try[IBucket],
	var s3SecretKey: String,
	var s3AccessKey: String,
	var s3EndPoint: String,
	var writerInstances: Int
)

/**
 * Describes an Http servlet that attempts to serve TTS requests.
 *
 * This class supports an IConfiguration of:
 *
 * 	This class creates it's own local configuration slot.
 * 		- Given by InetAddress.getLocalHost.getHostName
 *
 * 	example:
 *
 * 	localHostName {
 * 		rootPath: String of valid directory.
 * 		fileSystemType: String of "local" | "s3"
 * 		s3AccessKey: String
 * 		s3SecretKey: String
 * 		s3EndPoint: String :> valid URL path.
 *
 * 		filePrefix: String
 * 		fileSuffix: String
 * 		crypto: String of valid MessageDigest instance key.
 * 		writerInstances: Int
 * 		httpGetTimeout: String of parsable FiniteDuration.
 * 		cacheSize: Int
 *
 * 		staticEngine { /* configuration */ }
 *
 * 		engines { /* Engine configurations */ }
 * 	}
 */
class TtsProxyServlet(
		audioSystem: IAudioSystem,
		localFileSystem: ILocalFileSystem,
		configService: IConfigService,
		trackerService: ITrackerService,
		val configHost: String,
		configNodeIn: String
	) extends
		HttpServlet with IConfigurable
	{

	Utils.setTrackerService(trackerService)

	/**
	 * This will resolve a good config node to place this proxy configuration.
	 */
	val configNode =
		localFileSystem.resolvePath(
			configNodeIn,
			InetAddress.getLocalHost.getHostName.replace('.', '-')
		)

	private[this] val staticEngine =
		new StaticTtsEngine(
			audioSystem,
			localFileSystem,
			configService,
			configHost,
			localFileSystem.resolvePath(configNode, "staticEngine")
		)

	private[this] val log =
		Utils.getLogger(this)

	private[this] val track =
		Utils.getTracker(Constants.trackerKey)

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
	private[this] val kEngines = "engines"
	private[this] val kStaticEngine = "staticEngine"
	private[this] val kCacheSize = "cacheSize"

	private[this] val kFileSystemType = "fileSystemType"
	private[this] val kS3AccessKey = "s3AccessKey"
	private[this] val kS3SecretKey = "s3SecretKey"
	private[this] val kS3EndPoint = "s3EndPoint"

	private[this] val cS3FileSystemType = "s3"
	private[this] val cLocalFileSystemType = "local"

	/**
	 * Configuration state
	 */

	/**
	 * This will structure a default config state.
	 */
	private[this] val state = {
		val rootPath = localFileSystem.normalize("tts-cache")

		TtsProxyState(
			crypto = "SHA1",
			filePrefix = "",
			fileSuffix = ".wav",
			httpGetTimeoutDur = 10 seconds,
			rootPath = rootPath,
			fileSystemType = "local",
			bucket = localFileSystem.getBucket(rootPath),
			s3SecretKey = "",
			s3AccessKey = "",
			s3EndPoint = "",
			writerInstances = 5
		)
	}

	private[this] val actorSystem =
		getActorSystem

	private[this] val fileWriter = actorSystem.actorOf(Props(
		classOf[FileRouter],
		state.writerInstances
	))

	/**
	 * This is meant to hold a temporary state for staging file system changes.
	 */
	private[this] val stagedChanges = mutable.Map[String, String]()

	/**
	 * This is meant to act as an convenient way of applying file system changes to the config state.
	 */
	private[this] val applyStaged = Map(
		kRootPath -> { v: String => state.rootPath = v },
		kFileSystemType -> { v: String => state.fileSystemType = v },
		kS3SecretKey -> { v: String => state.s3SecretKey = v },
		kS3AccessKey -> { v: String => state.s3AccessKey = v },
		kS3EndPoint -> { v: String => state.s3EndPoint = v }
	)

	/**
	 * This maps configuration keys to functionality within this class.
	 * - Any time this is used, the state should be synchronized.
	 */
	private[this] val propHandleMap = Map(
		kRootPath -> { pathIn:String =>
			stagedChanges.update(kRootPath, pathIn)
		},

		kFileSystemType -> { v: String => stagedChanges.update(kFileSystemType, v) },
		kS3AccessKey -> { v: String => stagedChanges.update(kS3AccessKey, v) },
		kS3SecretKey -> { v: String => stagedChanges.update(kS3SecretKey, v) },
		kS3EndPoint -> { v: String => stagedChanges.update(kS3EndPoint, v) },

		kFilePrefix -> { v:String => state.filePrefix = v },

		kFileSuffix -> { v:String => state.fileSuffix = v },

		kCrypto -> { v:String => state.crypto = v },

		kWriterInstances -> { v:String =>
			Try{ v.toInt }
			  .foreach{ state.writerInstances = _ }
		},

		kHttpGetTimeout -> { v: String =>
		  	Try {
				val dur = Duration(v)
				FiniteDuration(dur.toNanos, duration.NANOSECONDS)
			} match {
				case Success(result) =>
					state.httpGetTimeoutDur = result
				case Failure(err) =>
					log.error(s"Unable to parse Duration from $v", err.getMessage())
			}
		},

		kEngines -> { v: String =>
		  	// This will do nothing, this key is being handled by the EngineHandler.
			()
		},

		kStaticEngine -> { v: String =>
			// This will do nothing, this key is being handled by the StaticTtsEngine.
			()
		},

		kCacheSize -> { v: String =>
		  	Try{ v.toInt }.foreach {
				i: Int => cache.resize(i)
			}
		}
	)

	private[this] val watcher = configService(
		this,
		Iterable(
			kCrypto -> state.crypto,
			kFilePrefix -> state.filePrefix,
			kFileSuffix -> state.fileSuffix,
			kHttpGetTimeout -> state.httpGetTimeoutDur.toString(),
			kRootPath -> state.rootPath,
			kWriterInstances -> state.writerInstances.toString,
			kCacheSize -> cache.maxSize.toString
		)
	).toOption
	if (watcher.isEmpty)
		log.error(s"$this watcher is undefined, problem with ${watcher.getClass}")

	private[this] val engineRouter = actorSystem.actorOf(Props(
		classOf[EnginesProxy],
		configService,
		configHost,
		configNode + "/engines"
	))

	private[this] def sync[A](m: A) = state.synchronized(m)

	/**
	 * Simple function to describe how a single property is to be updated.
	 * @param key The key to update.
	 * @param v The value to update.
	 */
	private[this] def updateProp(key: String, v: String) {
		propHandleMap.get(key) match {
			case Some(f) => f(v)
			case None =>
				log.warn(s"Unknown config update key: $key")
		}
	}

	/**
	 * Callback to inform this object that updates have occurred.
	 * @param props The map of updates that have occurred.
	 */
	def configUpdate(props: Iterable[(String, String)]) {
		log.info(s"Updating with $props")
		sync {
			props.foreach {
				case (k, v) => updateProp(k, v)
			}

			// The propHandleMap may have populated the stagedChanges map.

			if (stagedChanges.size > 0) {

				// Determine which file system to use.
				// This will default to the local file system.
				val system = {
					val sysType = stagedChanges.getOrElse(kFileSystemType, state.fileSystemType)

					if (sysType == cLocalFileSystemType)
						localFileSystem

					else if (sysType == cS3FileSystemType) {
						// If S3 is desired, the correct credentials must be used.
						val access = stagedChanges.getOrElse(kS3AccessKey, state.s3AccessKey)
						val secret = stagedChanges.getOrElse(kS3SecretKey, state.s3SecretKey)
						val endPoint = stagedChanges.getOrElse(kS3EndPoint, state.s3EndPoint)

						localFileSystem.getS3(access, secret, endPoint) match {
							case Success(res) => res
							case Failure(err) =>
								log.error(s"Unable to load s3 file system.", err.getMessage())
								localFileSystem
						}
					}
					else {
						log.error(s"Bad configuration value for fileSystemType -> $sysType")
						localFileSystem
					}
				}

				val bucketPath = system.normalize(
					stagedChanges.getOrElse(kRootPath, state.rootPath)
				)

				system.getBucket(bucketPath) match {
					case Success(bucket) =>
						Try {
							// This will attempt to pull the stored files into the cache.
							fileWriter ! FileMsg.PullBucketNodesToF(
								bucket,
								key =>
									key.startsWith(state.filePrefix) &&
									  key.endsWith(state.fileSuffix) &&
									  cache.size < cache.maxSize,
								cache.put
							)
						} match {
							case Success(_) => ()
							case Failure(err) =>
								log.error("The fileWriter router cannot be reached.", err)
						}

						state.bucket = Success(bucket)
					case Failure(err) =>
						log.error(s"Unable to retrieve bucket: $bucketPath", err)
						state.bucket = Failure(err)
				}

				stagedChanges foreach {
					case (k, v) => applyStaged get k match {
						case Some(f) => f(v)
						case None =>
							log.warn(s"Bad config staged: $k -> $v")
					}
				}
				stagedChanges.clear()
			}
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

	private[this] val audioContentType = "audio/x-wav"

	/**
	 * Attempts to copy the audio reader to the http response.
	 * @param resp The response to write to.
	 * @param reader The reader to reader from.
	 */
	private[this] def tryCopyReader(resp: HttpServletResponse, reader: IAudioReader) = Try{
		Utils.using( reader ) { reader =>
			Utils.using( resp.getOutputStream ) { outStream =>

				resp.setContentType(audioContentType)
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
				resp.sendError(n, "TTS engine cannot handle your request.")
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
							track.put("PipeResponse:Failure", 1)
						case _ =>
							track.put("CallEngine:Success", 1)
							()
					}

				case EngineMsg.CallEngineReply(Failure(err: EngineNotAvailable)) =>
					log.error(err.getMessage)
					track.put("EngineNotFound", 1)
					track.put("HttpResp:404", 1)
					resp.sendError(404, "TTS engine not available.")

				case EngineMsg.CallEngineReply(Failure(err: TimeoutException)) =>
					log.error("Unable to call engine", err.getMessage)
					track.put("CallEngine:Timeout", 1)

					checkCache(bucket) // Try again until failure

				case EngineMsg.CallEngineReply(Failure(err)) =>
					log.error("Unable to call engine.", err)
					track.put("HttpResp:500", 1)
					resp.sendError(500, "TTS engines are unavailable.")

				case _ =>
					log.error("Engine response was invalid.")
					track.put("InvalidEngineResponse", 1)
					track.put("HttpResp:500", 1)
					resp.sendError(500, "TTS engines were unable to handle request.")
			}
		} match {
			case Failure(err) =>
				log.error(err.getMessage)
				track.put(err.getClass.getName, 1)
				track.put("HttpResp:500", 1)
				resp.sendError(500, "Task timed out.")

			case Success(_) => ()
		}

		def checkCache(bucket: IBucket): Unit = {

			val cacheLookup = this.cache.get(fileKey)

			if (cacheLookup.isDefined) {
				track.put(cacheHit, 1)

				val respStream = new ByteArrayInputStream(cacheLookup.get)
				copyToResp(respStream, resp)
				track.put("HttpResp:200", 1)
			}
			else {
				track.put(cacheMiss, 1)

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
		}

		bucket match {
			case Success(bucket) => checkCache(bucket)
			case Failure(err) =>
				log.error(err.getMessage())
				resp.sendError(500, "Server file system currently unavailable.")
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
					resp.sendError(501, "Cannot translate request.")
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

				track.put("HttpResp:500", 1)
				resp.sendError(500, "Task timed out.")
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
						case -1 =>
							handleSynth(phrase, voice, resp)

						case i =>
							handleSpell(phrase, voice.substring(0, i), resp)
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

	/**
	 * This is used by the commands to insert a file configuration into service.
	 * 	Example: configPutFile /ttsConfiguration ~/config.cfg
	 *
	 * @param rootNode The root node to branch off of.
	 * @param path The local file path to the file.
	 */
	def configPutFile(rootNode: String, path: String) = Try{
		val file = new java.io.File(path)
		val stream = new java.io.FileInputStream(file)
		watcher.get.putData(rootNode, stream)
	}.flatten
}
