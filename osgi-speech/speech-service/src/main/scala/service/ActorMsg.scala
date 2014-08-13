package com.cooper.osgi.speech.service

import javax.servlet.http.HttpServletResponse
import scala.util.Try

object ActorMsg {
	trait Msg
	case class CallProxy(voice: String, speak: String, resp: HttpServletResponse) extends Msg
	case class UpdateProps(props: Iterable[(String, String)]) extends Msg
	case class UpdatePropsMap(map: Map[String, String]) extends Msg
	case class ConfigPutFile(path: String) extends Msg

	trait Reply
	case class CallProxyReply(res: Try[Unit]) extends Reply
}
