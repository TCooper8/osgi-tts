package com.cooper.osgi.io

import java.io.{Closeable, InputStream}

/**
 * Represents a common interface for implementing different file systems.
 * 	- The goal of this is implement multiple OSGi file systems with a common interface for versatility.
 * 	- All functionality of the implementation should be fail-safe.
 */
trait INode extends Closeable with AutoCloseable {
	/**
	 * Represents the map of this node's children, associated by name:String.
	 */
	val children: Map[String, INode]

	/**
	 * The name of this node.
	 */
	val name: String

	/**
	 * This node's parent node.
	 */
	val parent: INode

	/**
	 * The absolute path to this node.
	 */
	val path: String

	/**
	 * Attempts to map the given relative key to the associated node.
	 * @param key The key associated with the desired node.
	 * @return Returns Left(node) if successful, else Right(error).
	 */
	def apply(key: String): Either[INode, Throwable]

	/**
	 * Cleans up any resources allocated to this object.
	 */
	def close(): Unit

	/**
	 * Returns an InputStream to the content data of this node.
	 * @return Returns Left(stream) if content is available, else Right(error).
	 */
	def content: Either[InputStream, Throwable]

	/**
	 * Attempts to delete the key associated node.
	 * @param key The key associated with the node to delete.
	 */
	def delete(key: String): Unit

	/**
	 * Attempts to write the given stream to a new node with the name of the given key.
	 * 	- Note: Partial failure should be handled on the implementation side.
	 *
	 * @param inStream The stream to consume.
	 * @param key The key to the new node.
	 * @return Returns None if successful, else Some(error).
	 */
	def write(inStream: InputStream, key: String): Option[Throwable]

	/**
	 * Attempts to copy the given node to a new child of this node.
	 * @param node The node to copy.
	 * @return Returns None if successful, else Some(error).
	 */
	def write(node: INode): Option[Throwable]
}
