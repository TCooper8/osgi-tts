package com.cooper.osgi.io

import java.util.Date
import scala.util.Try
import java.io.InputStream

trait IFileSystem {
	/**
	 * Specifies the required or default directory separator.
	 */
	val separator: String

	/**
	 * Creates a new bucket for containing objects.
	 * @param path The path to use as a reference to the new bucket.
	 * @return Returns Success(bucket) or Failure(error).
	 */
	def createBucket(path: String): Try[IBucket]

	/**
	 * Normalizes the given path. See : java.util.Path.normalize(path) method.
	 * @param path The path to normalize.
	 * @return Returns the normalized path.
	 */
	def normalize(path: String): String

	def resolvePath(parentPath: String, childPath: String): String

	def getLastModified(bucket: String, path: String): Try[Date]

	def listBuckets: Try[Iterable[IBucket]]

	def deleteNode(bucket: String, key: String): Try[Unit]
	def deleteBucket(path: String): Try[Unit]

	def getNode(bucket: String, key: String): Try[INode]
	def getBucket(path: String): Try[IBucket]

	def read(bucket: IBucket, key: String): Try[InputStream]
	def read(bucket: String, key: String): Try[InputStream]

	def rename(bucket: IBucket, dst: String): Try[IBucket]
	def rename(node: INode, dst: String): Try[INode]

	def write(bucket: IBucket, inStream: InputStream, key: String): Try[INode]
	def write(bucketName: String, inStream: InputStream, key: String): Try[INode]
}

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

trait INode extends Ordered[INode] {
	def content: Try[InputStream]

	def key: String

	def lastModified: Try[Date]

	def path: String

	def parent: Try[IBucket]

	def write(inStream: InputStream): Try[Unit]

	override def compare(that: INode): Int = this.path compare that.path
}
