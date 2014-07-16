package com.cooper.osgi.io.s3

import com.cooper.osgi.io.{INode, IFileSystem}
import java.io.InputStream
import java.util.Date
import scala.util.Try

case class Node(fs: IFileSystem, parentKey: String, key: String) extends INode {

	def content: Try[InputStream] =
		fs.read(parentKey, key)

	def lastModified: Try[Date] =
		fs.getLastModified(parentKey, key)

	def write(inStream: InputStream): Try[Unit] =
		fs.write(parentKey, inStream, key).map{ _ => Unit }

	def path: String =
		this.key

	def parent =
		fs.getBucket(parentKey)
}
