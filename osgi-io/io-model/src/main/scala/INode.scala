package com.cooper.osgi.io

import java.io.InputStream
import java.util.Date
import scala.util.Try

/**
 * Represents an interface to a file system node, where raw data can be stored.
 */
trait INode extends Ordered[INode] {
	/**
	 * Attempts to retrieve the raw data as a stream from this node.
	 * @return Returns Success(inStream) if successful, else Failure(err).
	 */
	def content: Try[InputStream]

	/**
	 * The name associated with this node.
	 * @return Returns the local-most path segment of this node.
	 */
	def key: String

	/**
	 * Attempts to retrieve this objects last modified date.
	 * @return Returns Success(Date) if successful, else Failure(err).
	 */
	def lastModified: Try[Date]

	/**
	 * The path to this node.
	 * @return Returns the canonical path associated with this bucket.
	 */
	def path: String

	/**
	 * Attempts to retrieve the bucket that contains this node.
	 * @return Returns Success(bucket) if successful, else Failure(err).
	 */
	def parent: Try[IBucket]

	/**
	 * Attempts to write the given input stream of data to this node.
	 * 	- Partial failure is guaranteed against when calling this method.
	 * @param inStream The input stream to copy.
	 * @return Returns Success(Unit) if successful, else Failure(error).
	 */
	def write(inStream: InputStream): Try[Unit]

	/**
	 * Compares this object to another object, for ordering purposes.
	 * @param that The object to compare.
	 * @return Returns the Integer ordering of this object, relative to the other object.
	 */
	override def compare(that: INode): Int = this.path compare that.path
}
