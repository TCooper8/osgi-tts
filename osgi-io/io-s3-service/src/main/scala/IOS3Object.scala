package com.cooper.osgi.io.s3

import com.amazonaws.services.s3.model.S3Object
import com.amazonaws.services.s3.AmazonS3Client
import com.cooper.osgi.io.{IOCannotWrite, IOCannotOpen, INode}
import java.io.InputStream
import Functional.eitherT

case class IOS3Object(
		s3Obj: S3Object,
		val parent: IOS3Bucket,
		conn: AmazonS3Client
	) extends INode {

	val children: Map[String, INode] = Map()

	val name: String = s3Obj.getKey()
	val path: String = parent.name

	/**
	 * Attempts to map the given relative key to the associated node.
	 * @param key The key associated with the desired node.
	 * @return Returns Left(node) if successful, else Right(error).
	 */
	def apply(key: String): Either[INode, Throwable] =
		Right(IOCannotOpen(s"$name Cannot open child object from S3Object."))

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
	def delete(key: String) { }

	/**
	 * Attempts to write the given stream to a new node with the name of the given key.
	 * - Note: Partial failure should be handled on the implementation side.
	 *
	 * @param inStream The stream to consume.
	 * @param key The key to the new node.
	 * @return Returns None if successful, else Some(error).
	 */
	def write(inStream: InputStream, key: String): Option[Throwable] =
		Some(IOCannotWrite(s"$name Cannot create new object from S3 object."))

	/**
	 * Attempts to copy the given node to a new child of this node.
	 * @param node The node to copy.
	 * @return Returns None if successful, else Some(error).
	 */
	def write(node: INode): Option[Throwable] =
		Some(IOCannotWrite(s"$name Cannot create new object from S3 object."))
}
