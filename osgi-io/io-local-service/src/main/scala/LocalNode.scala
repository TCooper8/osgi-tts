package com.cooper.osgi.io.local

import com.cooper.osgi.io.{IFileSystem, IBucket, INode}
import java.io.InputStream
import scala.util.Try
import java.util.Date

case class LocalNode(fs: IFileSystem, parentPath: String, key: String) extends INode {
	def content: Try[InputStream] =
		fs.read(parentPath, this.key)

	def lastModified: Try[Date] =
		fs.getLastModified(parentPath, this.key)

	val path: String =
		fs.resolvePath(parentPath, key)

	def write(inStream: InputStream): Try[Unit] =
		fs.write(parentPath, inStream, key).map(_ => Unit)

	def parent: Try[IBucket] =
		fs.getBucket(parentPath)
}
