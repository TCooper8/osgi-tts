package com.cooper.osgi.io.local

import com.cooper.osgi.io.{IFileSystem, INode, IBucket}
import scala.util.Try
import java.io.{File, InputStream}
import java.util.Date

case class LocalBucket(fs: IFileSystem, path: String) extends IBucket {

	private[this] lazy val dir: File = new File(path)

	override def key = dir.getName()

	override def creationDate: Try[Date] =
		fs.getLastModified("/", path)

	override def listNodes: Try[Iterable[INode]] = Try {
		dir.listFiles.map {
			f => if (f.isFile) Some(LocalNode(fs, this.path, f.getName)) else None
		}.flatten.toIterable
	}

	override def listBuckets: Try[Iterable[IBucket]] = Try {
		dir.listFiles.map {
			f => if (f.isDirectory) Some(LocalBucket(fs, f.getPath)) else None
		}.flatten.toIterable
	}

	override def delete(key: String): Try[Unit] =
		fs.deleteNode(path, key)

	override def read(key: String): Try[INode] =
		fs.getNode(path, key)

	override def write(inStream: InputStream, key: String): Try[INode] =
		fs.write(this.path, inStream, key)
}
