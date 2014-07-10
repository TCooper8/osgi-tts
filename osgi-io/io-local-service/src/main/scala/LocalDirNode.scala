package com.cooper.osgi.io.local

import java.io.{FileOutputStream, InputStream, File}
import com.cooper.osgi.io.{IONoContent, IOUtils, INode}
import com.cooper.osgi.utils.{StatTracker, MaybeLog, Logging}
import scala.util.{Failure, Try, Random}
import Functional.eitherT

/**
 * Represents a java.io.File<Directory> wrapped in a fail-safe implementation with an INode interface.
 * @param file The io File to use.
 */
case class LocalDirNode(file: File) extends INode {
	private[this] val log = Logging(this.getClass)

	private[this] val track = StatTracker(Constants.trackerKey)

	private[this] val maybe = MaybeLog(log, track)

	private[this] val rand = new Random()

	/**
	 * Represents the map of this node's children, associated by name:String.
	 */
	lazy val children: Map[String, INode] = maybe {
		val files = file.listFiles()
		if (files == null) Map[String, INode]()
		else files.flatMap {
			file =>
				val key = file.getName()
				if (file.isFile()) Some(key -> LocalFileNode(file))
				else if (file.isDirectory()) Some(key -> LocalDirNode(file))
				else None
		}.toMap
	} getOrElse(Map[String, INode]())

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
	 * Creates a new name with a randomized prefix for creating temporary files.
	 * @param filename The filename to add a prefix.
	 * @return Returns a new filename.
	 */
	private[this] def tmpFilename(filename: String) = { s"${System.nanoTime() % rand.nextLong()}" + filename }

	/**
	 * Used to write an input stream to a target file.
	 * @param target The file to target.
	 * @param inStream The stream to consume.
	 * @return Returns Some(node) if successful, else None.
	 *         Partial failure returns None.
	 */
	/*private[this] def writeFile(target: File, inStream: InputStream) = eitherT {
		val tmpFile = new File(this.file, tmpFilename(target.getName()))
		IOUtils.using(new FileOutputStream(tmpFile)) {
			outStream =>
				val _ = IOUtils.copy(inStream, outStream)
				assert(tmpFile.renameTo(target))
				LocalFileNode(target)
		}
	}*/

		/*SafeFile(file, tmpFilename(target.getName())) flatMap {
			tmpFile => IOUtils.using(new FileOutputStream(tmpFile)) {
				outStream =>
					val _ = IOUtils.copy(inStream, outStream)
					if (tmpFile.renameTo(target)) Some(LocalFileNode(target))
					else None
			}
		}
	}.flatten*/

	def apply(key: String): Either[INode, Throwable] = eitherT {
		val child = new File(this.file, key)
		if (child.isFile()) LocalFileNode(child)
		else LocalDirNode(child)
	}

	def close() { }

	/**
	 * Returns an InputStream to the content data of this node.
	 * @return This always returns None, as these directories cannot contain data.
	 */
	val content: Either[InputStream, Throwable] =
		Right(IONoContent(s"$path is directory, does not contain any content."))

	/**
	 * Attempts to delete the key associated node.
	 * @param key The key associated with the node to delete.
	 */
	def delete(key: String) {
		Try {
			val child = new File(this.file, key)
			file.delete()
		}
	}
		/*maybe{
		SafeFile(this.file, key) foreach {
			file => file.delete()
		}
	}*/

	/**
	 * Attempts to write the given stream to a new node with the name of the given key.
	 * @param inStream The stream to consume.
	 * @param key The key to the new node.
	 * @return Returns Some(node) if successful, else None.
	 *         Partial failure returns None.
	 */
	def write(inStream: InputStream, key: String): Option[Throwable] =
		Try {
			val target = new File(this.file, key)
			target.getParentFile().mkdirs()
			val tmpFile = new File(this.file, tmpFilename(key))
			IOUtils.using(new FileOutputStream(tmpFile)) {
				outStream =>
					val _ = IOUtils.copy(inStream, outStream)
					assert(tmpFile.renameTo(target))
					LocalFileNode(target)
			}
		} match {
			case Failure(err) => Some(err)
			case _ => None
		}

	/*: Option[INode] = {
		SafeFile(file, key) flatMap {
			file => writeFile(file, inStream)
		}
	}*/

	/**
	 * Attempts to copy the given node to a new child of this node.
	 * @param node The node to copy.
	 * @return Returns Some(child) if successful, else None.
	 */
	def write(node: INode): Option[Throwable] =
		node.content match {
			case Left(inStream) => write(inStream, node.name)
			case Right(err) => Some(err)
		}
}
