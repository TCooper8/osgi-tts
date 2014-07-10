package com.cooper.osgi.io.s3

import com.amazonaws.services.s3.model.{S3Object, S3ObjectSummary}
import com.amazonaws.services.s3.AmazonS3Client
import com.cooper.osgi.io.{IOCannotOpen, IOCannotWrite, INode}
import java.io.InputStream
import Functional.eitherT

case class IOS3Summary(
		summary: S3ObjectSummary,
		val parent: IOS3Bucket,
		conn: AmazonS3Client
	) extends INode {

	private[this] lazy val s3Obj: S3Object = conn.getObject(parent.name, this.name)

	val children: Map[String, INode] = Map()

	val name: String = summary.getKey()

	val path: String = summary.getBucketName()

	/**
	 * Attempts to map the given relative key to the associated node.
	 * @param key The key associated with the desired node.
	 * @return Returns Left(node) if successful, else Right(error).
	 */
	def apply(key: String): Either[INode, Throwable] =
		Right(IOCannotOpen("Cannot open child object from S3Object."))

	/**
	 * Cleans up any resources allocated to this object.
	 */
	def close() { }

	/**
	 * Returns an InputStream to the content data of this node.
	 * @return Returns Left(stream) if content is available, else Right(error).
	 */
	def content: Either[InputStream, Throwable] =
		eitherT{ s3Obj.getObjectContent() }

	/**
	 * Attempts to delete the key associated node.
	 * @param key The key associated with the node to delete.
	 */
	def delete(key: String) {
		parent.delete(key)
	}

	/**
	 * Attempts to write the given stream to a new node with the name of the given key.
	 * - Note: Partial failure should be handled on the implementation side.
	 *
	 * @param inStream The stream to consume.
	 * @param key The key to the new node.
	 * @return Returns None if successful, else Some(error).
	 */
	def write(inStream: InputStream, key: String): Option[Throwable] =
		Some(IOCannotWrite("Cannot create new object from S3 object."))

	/**
	 * Attempts to copy the given node to a new child of this node.
	 * @param node The node to copy.
	 * @return Returns None if successful, else Some(error).
	 */
	def write(node: INode): Option[Throwable] =
		Some(IOCannotWrite("Cannot create new object from S3 object."))
}
