package com.cooper.osgi.speech.service

import com.cooper.osgi.io.IBucket
import com.ning.http.client.Response
import java.io.InputStream
import scala.util.Try
import javax.servlet.http.HttpServletResponse

object Constants {
	val trackerKey = "***REMOVED***"

	trait Msg
	case class WriteFileMsg(bucket: IBucket, inStream: InputStream, key: String) extends Msg
	case class CallEngine(voice: String, speak: String) extends Msg
	case class CallProxy(voice: String, speak: String, resp: HttpServletResponse) extends Msg
	case class UpdateWriterCount(n: Int) extends Msg
	case class UpdateAlias(alias:String) extends Msg
	case class UpdateProps(props: Iterable[(String, String)]) extends Msg
	case class UpdatePropsMap(map: Map[String, String]) extends Msg
	case class ConfigPutFile(path: String) extends Msg

	trait Reply
	case class CallEngineReply(data: Try[Response]) extends Reply
	case class CallProxyReply(res: Try[Unit]) extends Reply
}
