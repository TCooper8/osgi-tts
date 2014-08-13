package com.cooper.osgi.io

import scala.util.Try
import java.util.Date
import java.io.InputStream

/**
 * Provides an interface to a file system and is the factory for objects to access
 * files and other objects in the file system.
 */
trait IFileSystem {
	/**
	 * Specifies the required or default directory separator.
	 */
	val separator: String

	/**
	 * Attempts to create a new bucket for containing objects.
	 * 	- Note: If bucket already exists, this will simply return the existing one.
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

	/**
	 * Resolves a normalized path of a root / childPath.
	 * @param parentPath The root-most path part.
	 * @param childPath The child-most path part.
	 * @return Returns the normalized parentPath/childPath
	 */
	def resolvePath(parentPath: String, childPath: String): String

	/**
	 * Attempts to retrieve the node's last modified date.
	 * @param bucket The root bucket to lookup the key associated node.
	 * @param path The path to the desired node.
	 * @return Returns Success(Date) if successful, else Failure(err).
	 */
	def getLastModified(bucket: String, path: String): Try[Date]

	/**
	 * Attempts to list all root buckets.
	 * @return Returns Success(bucket iterable) if successful, else Failure(err).
	 */
	def listBuckets: Try[Iterable[IBucket]]

	/**
	 * Attempts to delete a node.
	 * @param bucket The root bucket containing the node.
	 * @param key The key associated with the node.
	 * @return Returns Success(Unit) if successful, else Failure(err).
	 */
	def deleteNode(bucket: String, key: String): Try[Unit]

	/**
	 * Attempts to delete a bucket.
	 * @param path The path associated with the bucket.
	 * @return Returns Success(Unit) if successful, else Failure(err).
	 */
	def deleteBucket(path: String): Try[Unit]

	/**
	 * Attempts to retrieve a node from within a bucket.
	 * @param bucket The path of the bucket.
	 * @param path The path associated with the node.
	 * @return Returns Success(node) if successful, else Failure(err).
	 */
	def getNode(bucket: String, path: String): Try[INode]

	/**
	 * Attempts to retrieve a bucket.
	 * @param path
	 * @return
	 */
	def getBucket(path: String): Try[IBucket]

	/**
	 * Attempts to read the key associated node.
	 * @param bucket The bucket to read from.
	 * @param key The key associated with the desired node.
	 * @return Returns Success(inStream) if successful, else Failure(err).
	 */
	def read(bucket: IBucket, key: String): Try[InputStream]

	/**
	 * Attempts to read the key associated node.
	 * @param bucket The bucket path where the node might be found.
	 * @param key The key associated with the desired node.
	 * @return Returns Success(inStream) if successful, else Failure(err).
	 */
	def read(bucket: String, key: String): Try[InputStream]

	/**
	 * Attempts to rename a bucket.
	 * @param bucket The bucket to rename.
	 * @param dst The new name of the bucket.
	 * @return Returns Success(bucket) if successful, else Failure(err).
	 */
	def rename(bucket: IBucket, dst: String): Try[IBucket]

	/**
	 * Attempts to rename a node.
	 * @param node The node to rename.
	 * @param dst The new name of the node.
	 * @return Returns Success(node) if successful, else Failure(err).
	 */
	def rename(node: INode, dst: String): Try[INode]

	/**
	 * Attempts to write new data to the key associated node.
	 * 	- Partial failure is guaranteed against.
	 * @param bucket The root bucket where the node might be found.
	 * @param inStream The stream of data to write to the node.
	 * @param key The key associated with the desired node.
	 * @return Returns Success(node) if successful, else Failure(err).
	 */
	def write(bucket: IBucket, inStream: InputStream, key: String): Try[INode]

	/**
	 * Attempts to write new data to the key associated node.
	 * @param bucketName The bucket path where the node might be found.
	 * @param inStream The stream of data to write to the node.
	 * @param key The key associated with the desired node.
	 * @return Returns Success(node) if successful, else Failure(err).
	 */
	def write(bucketName: String, inStream: InputStream, key: String): Try[INode]
}
