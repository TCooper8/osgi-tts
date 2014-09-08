package com.cooper.osgi.io.service

import java.io.{FileInputStream, FileOutputStream, InputStream, File}
import scala.util.{Failure, Success, Random, Try}
import java.util.Date
import java.nio.file.Paths
import com.cooper.osgi.io._

class LocalFileSystem() extends ILocalFileSystem {

	val separator: String = File.separator

	def getS3(accessKey: String, secretKey: String, endPoint: String): Try[IFileSystem] = {
		S3FileSystem(accessKey, secretKey, endPoint) match {
			case Success(s3) =>
				Success(s3)
			case Failure(err) => Failure(err)
		}
	}

	def createBucket(path: String): Try[IBucket] = Try {
		val file = new File(path)
		file.mkdir()
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
		val parent = dst.getParentFile()
		parent.mkdir()

		val tmp = new File(parent, s"${System.nanoTime % Random.nextLong}${dst.getName}")
		val outStream = new FileOutputStream(tmp)

		val res = Try {
			val mBytes = inStream.available()
			val nBytes = Utils.copy(inStream, outStream)

			(mBytes, nBytes)
		}

		outStream.flush()
		outStream.close()

		res match {
			case Success((mBytes, nBytes)) => Try {
				assert(nBytes == mBytes, s"Partial failure when copying stream to $key. $nBytes copied, $mBytes expected.")
				assert(tmp.renameTo(dst), s"Unable to rename $tmp to $dst")

				LocalNode(this, bucketName, key)
			}
			case Failure(err) => Failure(err)
		}
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

case class LocalBucket(fs: IFileSystem, path: String) extends IBucket {

	private[this] lazy val dir: File = new File(path)

	override def key = dir.getName()

	override def creationDate: Try[Date] =
		fs.getLastModified("/", path)

	override def listNodes: Try[Iterable[INode]] = Try {
		dir.listFiles.map {
			f => if (f.isFile) Some(LocalNode(fs, this.path, f.getName)) else None
		}.flatten.toIterable
	}

	override def listBuckets: Try[Iterable[IBucket]] = Try {
		dir.listFiles.map {
			f => if (f.isDirectory) Some(LocalBucket(fs, f.getPath)) else None
		}.flatten.toIterable
	}

	override def delete(key: String): Try[Unit] =
		fs.deleteNode(path, key)

	override def read(key: String): Try[INode] =
		fs.getNode(path, key)

	override def write(inStream: InputStream, key: String): Try[INode] =
		fs.write(this.path, inStream, key)


}

case class LocalNode(fs: IFileSystem, parentPath: String, key: String) extends INode {
	def content: Try[InputStream] =
		fs.read(parentPath, this.key)

	def lastModified: Try[Date] =
		fs.getLastModified(parentPath, this.key)

	val path: String =
		fs.resolvePath(parentPath, key)

	def write(inStream: InputStream): Try[Unit] =
		fs.write(parentPath, inStream, key).map(_ => Unit)

	def parent: Try[IBucket] =
		fs.getBucket(parentPath)
}
