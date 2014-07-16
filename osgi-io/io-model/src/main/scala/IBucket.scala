package com.cooper.osgi.io

import java.util.Date
import scala.util.Try
import java.io.InputStream

trait IBucket extends Ordered[IBucket] {
	def creationDate: Try[Date]

	def delete(key: String): Try[Unit]

	def key: String

	def listNodes: Try[Iterable[INode]]

	def listBuckets: Try[Iterable[IBucket]]

	def path: String

	def read(key: String): Try[INode]

	def write(inStream: InputStream, key: String): Try[INode]

	override def compare(that: IBucket): Int = this.path compare that.path
}
