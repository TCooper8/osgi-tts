package com.cooper.osgi.speech.service

import com.cooper.osgi.io.INode
import com.ning.http.client.Response
import java.io.InputStream
import scala.util.Try

object Constants {
	val trackerKey = "***REMOVED***"

	trait Msg
	case class WriteFileMsg(rootFile: INode, inStream: InputStream, key: String) extends Msg
	case class CallEngine(voice: String, speak: String) extends Msg
	case class UpdateWriterCount(n: Int) extends Msg
	case class UpdateAlias(alias:String) extends Msg
	case class UpdateProps(props: Iterable[(String, String)]) extends Msg

	trait Reply
	case class CallEngineReply(data: Try[Response]) extends Reply
}
