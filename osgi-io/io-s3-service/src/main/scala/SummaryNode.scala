package com.cooper.osgi.io.s3

import com.cooper.osgi.io.{INode, IBucket, IFileSystem}
import com.amazonaws.services.s3.model.S3ObjectSummary
import scala.util.{Success, Try}
import java.io.InputStream
import java.util.Date

case class SummaryNode(fs: IFileSystem, bucket: IBucket, summary: S3ObjectSummary) extends INode {

	def content: Try[InputStream] =
		fs.read(bucket.key, this.key)

	def key: String =
		summary.getKey()

	def lastModified: Try[Date] = Try {
		summary.getLastModified()
	}

	def write(inStream: InputStream): Try[Unit] =
		fs.write(bucket.key, inStream, key).map{ _ => Unit }

	def path: String =
		fs.resolvePath(bucket.path, this.key)

	def parent = Success(bucket)
}
