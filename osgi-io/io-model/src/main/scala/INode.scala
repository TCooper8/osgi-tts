package com.cooper.osgi.io

import java.io.InputStream
import java.util.Date
import scala.util.Try

trait INode extends Ordered[INode] {
	def content: Try[InputStream]

	def key: String

	def lastModified: Try[Date]

	def path: String

	def parent: Try[IBucket]

	def write(inStream: InputStream): Try[Unit]

	override def compare(that: INode): Int = this.path compare that.path
}
