package com.cooper.osgi.io.s3

import com.amazonaws.services.s3.model.{ObjectMetadata, Bucket}
import com.cooper.osgi.io.{IONoContent, INode}
import com.amazonaws.services.s3.AmazonS3Client
import java.io.InputStream
import scala.collection.JavaConversions._
import scala.util.{Success, Failure, Try}

case class IOS3Bucket(
		bucket: Bucket,
		conn: AmazonS3Client,
		val parent: INode
	) extends INode {
	
	val children: Map[String, INode] =
		conn.listObjects(bucket.getName()
			).getObjectSummaries().toList
			.map(sum => (sum.getKey -> IOS3Summary(sum, this, conn)))
		  	.toMap
	
	val name: String = bucket.getName()

	val path: String = bucket.getName()

	/**
	 * Attempts to map the given relative key to the associated node.
	 * @param key The key associated with the desired node.
	 * @return Returns Left(node) if successful, else Right(error).
	 */
	def apply(key: String): Either[INode, Throwable] = {
		Try {
			conn.getObject(this.name, key)
		} match {
			case Success(child) => Left(IOS3Object(child, this, conn))
			case Failure(err) => Right(err)
		}
	}

	/**
	 * Returns an InputStream to the content data of this node.
	 * @return Returns Some(stream) if content is available, else None.
	 */
	def content: Either[InputStream, Throwable] =
		Right(IONoContent(s"$path is bucket, and does not contain any content."))

	/**
	 * Cleans up any resources allocated to this object.
	 */
	def close() { }

	/**
	 * Attempts to delete the key associated node.
	 * @param key The key associated with the node to delete.
	 */
	def delete(key: String) {
		Try { conn.deleteObject(this.name, key) }
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
		Try {
			conn.putObject(this.name, key, inStream, new ObjectMetadata())
		} match {
			case Failure(err) => Some(err)
			case Success(_) => None
		}

	/**
	 * Attempts to copy the given node to a new child of this node.
	 * @param node The node to copy.
	 * @return Returns None if successful, else Some(error).
	 */
	def write(node: INode): Option[Throwable] =
		node.content match {
			case Left(inStream) => this.write(inStream, node.name)
			case Right(err) => Some(err)
		}
}
