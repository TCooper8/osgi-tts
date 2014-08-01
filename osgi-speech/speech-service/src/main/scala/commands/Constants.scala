package com.cooper.osgi.speech.commands

import org.osgi.service.cm.ConfigurationAdmin
import scala.util.{Failure, Success, Try}
import scala.collection.mutable
import scala.collection.JavaConversions.asJavaDictionary

object Constants {
	private[this] def defaultProps: Map[String, String] = Map(
		"configHost" -> "localhost:2181",
		"configNode" -> "/tts"
	)

	def doGetCmd(configAdmin: ConfigurationAdmin, pid: String) = {
		key: String =>
			Try {
				val v = configAdmin.getConfiguration(pid).getProperties.get(key)
				println(s"$key = $v")
			} match {
				case Success(_) => ()
				case Failure(err) => println(err.getMessage())
			}
	}

	def doSetCmd(configAdmin: ConfigurationAdmin, pid: String)(key:String, value: Object) {
		Try {
			val config = configAdmin.getConfiguration(pid)
			val props = config.getProperties()

			if (props == null) {
				println(s"$pid properties are null, updating with default properties.")
				config.update(
					mutable.Map(
						defaultProps.+( (key, value) ).toSeq: _*
				))
			}
			else {
				props.put(key, value)
				config.update(props)
				println(s"$key = $value")
			}
		} match {
			case Success(_) => ()
			case Failure(err) => println(err.getMessage())
		}
	}
}
