package com.cooper.osgi.io.local

import com.cooper.osgi.io.{INode, IBucket, ILocalFileSystem}
import scala.util.{Random, Try}
import java.io.{FileInputStream, FileOutputStream, File, InputStream}
import java.util.Date
import java.nio.file.Paths

class LocalFileSystem() extends ILocalFileSystem {
	val separator: String = File.separator

	def createBucket(path: String): Try[IBucket] = Try {
		val file = new File(path)
		file.mkdirs()
		if (file.exists)
			assert(file.isDirectory, s"File $path is not a directory.")
		else
			assert(file.mkdir(), s"Unable to create directory $path")

		LocalBucket(this, file.getPath)
	}

	def resolvePath(parentPath: String, childPath: String): String =
		Paths.get(parentPath, childPath).toString()

	def getLastModified(bucket: String, key: String): Try[Date] = Try {
		val file = new File(bucket, key)
		new Date(file.lastModified)
	}

	def deleteNode(bucket: String, key: String): Try[Unit] =
		deleteFile(resolvePath(bucket, key))

	def deleteBucket(path: String): Try[Unit] =
		deleteFile(path)

	private[this] def deleteFile(path: String): Try[Unit] = Try {
		val file = new File(path)
		assert(file.delete(), s"Unable to delete file $file")
	}

	def getNode(bucket: String, key: String): Try[INode] = Try{
		LocalNode(this, bucket, key)
	}

	def getBucket(path: String): Try[IBucket] = Try{
		val file = new File(path)
		assert(file.exists, s"Directory $path does not exist.")
		assert(file.isDirectory, s"File $path is not a directory.")
		LocalBucket(this, file.getPath)
	}

	def rename(bucket: IBucket, dst: String): Try[IBucket] =
		renameFile(bucket.path, dst).map{
			f => LocalBucket(this, f.getPath) }

	def rename(node: INode, dst: String): Try[INode] =
		renameFile(node.path, dst).map{
			f => LocalNode(this, f.getParent, f.getName) }

	private[this] def renameFile(path: String, dstPath: String) = Try {
		val file = new File(path)
		val dst = new File(dstPath)
		assert(file.renameTo(dst), s"Unable to rename $path to $dstPath")
		dst
	}

	def write(bucket: IBucket, inStream: InputStream, key: String): Try[INode] =
		write(bucket.path, inStream, key)

	def write(bucketName: String, inStream: InputStream, key: String): Try[INode] = Try {
		val dst = new File(bucketName, key)
		val tmp = new File(bucketName, s"${System.nanoTime % Random.nextLong}$key")
		val outStream = new FileOutputStream(tmp)

		val res = Try {
			val mBytes = inStream.available()
			val nBytes = Utils.copy(inStream, outStream)

			assert(nBytes == mBytes, s"Partial failure when copying stream to $key. $nBytes copied, $mBytes expected.")
			assert(tmp.renameTo(dst), s"Unable to rename $tmp to $dst")

			LocalNode(this, bucketName, key)
		}

		outStream.flush()
		outStream.close()
		res
	}.flatten

	def listBuckets: Try[Iterable[IBucket]] = Try {
		File.listRoots.map {
			f => if (f.exists && f.isDirectory) Some(LocalBucket(this, f.getPath)) else None
		}.flatten.toIterable
	}

	def read(bucket: IBucket, key: String): Try[InputStream] =
		read(bucket.path, key)

	def read(bucket: String, key: String): Try[InputStream] = Try {
		new FileInputStream(new File(bucket, key))
	}

	def normalize(path: String): String =
		Paths.get(path).normalize.toString
}
