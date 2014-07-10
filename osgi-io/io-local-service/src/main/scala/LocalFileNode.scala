package com.cooper.osgi.io.local

import com.cooper.osgi.io.{IOCannotWrite, IOCannotOpen, INode}
import java.io.{FileInputStream, File, InputStream}
import com.cooper.osgi.utils.Logging
import Functional.eitherT

case class LocalFileNode(file: File) extends INode {
	private[this] implicit val log = Logging(this.getClass)

	/**
	 * Represents the map of this node's children, associated by name:String.
	 */
	val children: Map[String, INode] = Map()

	/**
	 * The name of this node.
	 */
	val name: String = file.getName()

	/**
	 * This node's parent node.
	 */
	lazy val parent: INode = LocalDirNode(file.getParentFile())

	/**
	 * The absolute path to this node.
	 */
	val path: String = file.getPath()

	/**
	 * Local files do not have any children.
	 * @return Returns Left(node) if successful, else Right(error).
	 */
	def apply(key: String): Either[INode, Throwable] =
		Right(IOCannotOpen(s"$path cannot open file $key. Files cannot have children."))

	/**
	 * Cleans up any resources allocated to this object.
	 */
	def close() { }

	/**
	 * Returns an InputStream to the content data of this node.
	 * @return Returns Left(stream) if content is available, else Right(error).
	 */
	def content: Either[InputStream, Throwable] =
		eitherT{ new FileInputStream(file) }

	/**
	 * Attempts to delete the key associated node.
	 * @param key The key associated with the node to delete.
	 */
	def delete(key: String) { }

	/**
	 * Local files cannot write children.
	 * @return Returns None if successful, else Some(error).
	 */
	def write(inStream: InputStream, key: String): Option[Throwable] =
		Some(IOCannotWrite(s"$name Cannot create new object from S3 object."))

	/**
	 * Local files cannot write children.
	 * @return Returns None if successful, else Some(error).
	 */
	def write(node: INode): Option[Throwable] =
		Some(IOCannotWrite(s"$name Cannot create new object from S3 object."))
}
