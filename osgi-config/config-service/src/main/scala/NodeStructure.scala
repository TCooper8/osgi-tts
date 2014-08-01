package com.cooper.osgi.config.service

import java.io.{InputStream, InputStreamReader}
import scala.util.Try
import com.typesafe.config.{ConfigFactory, Config}
import scala.collection.JavaConversions._

object NodeStructure {
	case class Node(name: String, data: String) extends Ordered[Node] {
		override def compare(that: Node): Int = name compare that.name
	}

	private[this] def toNodeStructure(cfg: Config) = Try {
		cfg.entrySet().map {
			entry =>
				val name = "/" + entry.getKey().replace('.', '/')
				val value = entry.getValue().render().replace('"'.toString, "")

				println(s"Node name: -> $name")
				println(s"Node value: -> $value")

				Node(name, value)
		}
	}

	def parse(inStream: InputStream): Try[Traversable[Node]] = Try {
		val reader = new InputStreamReader(inStream)
		val res =
			Try {ConfigFactory.parseReader(reader) }
			.flatMap(toNodeStructure)

		reader.close()
		res
	}.flatten
}
