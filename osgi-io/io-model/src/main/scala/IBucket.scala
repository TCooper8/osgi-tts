package com.cooper.osgi.io

import java.io.InputStream
import java.util.Date
import scala.util.Try

/**
 * Represents a file system bucket that objects can be placed in to.
 */
trait IBucket extends Ordered[IBucket] {
	/**
	 * Attempts to retrieve this objects creation date.
	 * @return Returns Success(Date) if successful, else Failure(err).
	 */
	def creationDate: Try[Date]

	/**
	 * Attempts to delete the key associated object from this bucket.
	 * @param key The key associated with the object.
	 * @return Returns Success(Unit) if successful, else Failure(err).
	 */
	def delete(key: String): Try[Unit]

	/**
	 * The name associated with this bucket.
	 * @return Returns the local-most path segment of this bucket.
	 */
	def key: String

	/**
	 * Attempts to retrieve an Iterable structure for the shallowest nodes available
	 * within this bucket.
	 * @return Returns Success(iterable) if successful, else Failure(err).
	 */
	def listNodes: Try[Iterable[INode]]

	/**
	 * Attempts to retrieve an Iterable structure for the shallowest buckets available
	 * within this bucket.
	 * - Note: If using S3; no buckets will be contained within other buckets.
	 * @return Returns Success(iterable) if successful, else Failure(err).
	 */
	def listBuckets: Try[Iterable[IBucket]]

	/**
	 * The path to this bucket.
	 * @return Returns the canonical path associated with this bucket.
	 */
	def path: String

	/**
	 * Attempts to read the key associated node.
	 * @param key The key associated with the desired node.
	 * @return Returns Success(node) if successful, else Failure(err).
	 */
	def read(key: String): Try[INode]

	/**
	 * Attempts to write a stream of data to this bucket as a node associated to the given key.
	 * 	- Partial failure is guaranteed against when calling this method.
	 * @param inStream The stream of data.
	 * @param key The key to associated with the given InputStream.
	 * @return Returns Success(node) if successful, else Failure(err).
	 */
	def write(inStream: InputStream, key: String): Try[INode]

	/**
	 * Compares this object to another object, for ordering purposes.
	 * @param that The object to compare.
	 * @return Returns the Integer ordering of this object, relative to the other object.
	 */
	override def compare(that: IBucket): Int = this.path compare that.path
}
