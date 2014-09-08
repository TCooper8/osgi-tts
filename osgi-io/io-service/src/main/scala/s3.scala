package com.cooper.osgi.io.service

import com.amazonaws.services.s3.AmazonS3Client
import java.io._
import scala.util.{Success, Try}
import java.util.Date
import java.nio.file.Paths
import com.amazonaws.services.s3.model.{Bucket => S3Bucket, PutObjectRequest, GetObjectRequest, S3ObjectSummary, ObjectMetadata}
import scala.collection.JavaConversions._
import com.cooper.osgi.io._
import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.{ClientConfiguration, Protocol}
import org.apache.commons.io.IOUtils
import scala.concurrent.duration._

object S3FileSystem {
	def apply(accessKey: String, secretKey: String, endPoint: String) = Try {
		new S3FileSystem(accessKey, secretKey, endPoint)
	}
}

class S3FileSystem(accessKey:String, secretKey:String, endPoint:String) extends IFileSystem {
	val separator: String = File.separator

	private[this] val client = makeClient

	/**
	 * This is just an internal call to spawn an S3Client.
	 * @return Returns a new AmazonS3Client.
	 */
	private[this] def makeClient = {
		val credentials = new BasicAWSCredentials(accessKey, secretKey)

		val config = new ClientConfiguration()

		config.setProtocol(Protocol.HTTP)
		config.setConnectionTimeout(10.seconds.toMillis.toInt)
		config.setMaxConnections(64)
		config.setMaxErrorRetry(3)

		val client = new AmazonS3Client(credentials, config)
		client.setEndpoint(endPoint)

		client
	}

	/**
	 * Creates a new bucket for containing objects.
	 * @param path The path to use as a reference to the new bucket.
	 * @return Returns Success(bucket) or Failure(error).
	 */
	def createBucket(path: String): Try[IBucket] = Try{
		val bucket = client.createBucket(path)
		assert(bucket != null, s"Cannot retrieve bucket $path")
		Bucket(this, client, bucket)
	}

	/**
	 * Attempts to delete the associated key -> node.
	 * @param bucket The root bucket containing the node.
	 * @param key The key associated with the node.
	 * @return Returns Success(Unit) if successful, else Failure(err).
	 */
	def deleteNode(bucket: String, key: String): Try[Unit] = Try {
		client.deleteObject(bucket, key)
	}

	def getLastModified(bucket: String, path: String): Try[Date] = Try{
		client.getObjectMetadata(bucket, path).getLastModified()
	}

	def getBucket(path: String): Try[IBucket] = Try {
		val ls = client.listBuckets()
		ls.find {
			bucket => bucket.getName == path
		}.map {
			bucket => Bucket(this, client, bucket)
		}.get
	}

	def listBuckets: Try[Iterable[IBucket]] = Try {
		val ls = client.listBuckets()
		ls.map {
			bucket => Bucket(this, client, bucket)
		}
	}

	def resolvePath(parentPath: String, childPath: String): String =
		Paths.get(parentPath, childPath).toString()

	def rename(bucket: IBucket, dst: String): Try[IBucket] = Try {
		bucket match {
			case Bucket(_, _, s3bucket) =>
				s3bucket.setName(dst)
				bucket

			case _ =>
				assert(false, s"$bucket is invalid IBucket type for this file system.")
				null
		}
	}

	def rename(node: INode, dst: String): Try[INode] = Try {
		node match {
			case Node(_, bucketName, key) =>
				val obj = client.getObject(bucketName, key)
				obj.setKey(dst)
				node
			case _ =>
				assert(false, s"$node is invalid INode type for this file system.")
				null
		}
	}

	def read(bucket: IBucket, key: String): Try[InputStream] =
		read(bucket.key, key)

	def read(bucket: String, key: String): Try[InputStream] = Try {
		val req = new GetObjectRequest(bucket, key)
		val obj = client.getObject(req)
		val content = obj.getObjectContent

		val data = IOUtils.toByteArray(content)
		new ByteArrayInputStream(data)
	}

	def write(bucket: IBucket, inStream: InputStream, key: String): Try[INode] =
		write(bucket.key, inStream, key)

	def write(bucketName: String, inStream: InputStream, key: String): Try[INode] = Try{
		val data = new ObjectMetadata()
		data.setContentLength(inStream.available())

		val req = new PutObjectRequest(bucketName, key, inStream, data)
		val res = client.putObject(req)
		Node(this, bucketName, key)
	}

	/**
	 * Normalizes the given path. See : java.util.Path.normalize(path) method.
	 * @param path The path to normalize.
	 * @return Returns the normalized path.
	 */
	def normalize(path: String): String =
		Paths.get(path).normalize().toString()

	def getNode(bucket: String, key: String): Try[INode] = Try {
		Node(this, bucket, key)
	}

	def deleteBucket(path: String): Try[Unit] = Try {
		client.deleteBucket(path)
	}
}

case class Bucket(fs: IFileSystem, client: AmazonS3Client, bucket: S3Bucket) extends IBucket {

	def creationDate: Try[Date] = Try {
		bucket.getCreationDate()
	}

	def listNodes: Try[Iterable[INode]] = Try {
		val ls = client.listObjects(this.key)
		ls.getObjectSummaries.map {
			summ => SummaryNode(fs, this, summ)
		}.toIterable
	}

	def key: String =
		bucket.getName()

	def write(inStream: InputStream, key: String): Try[INode] =
		fs.write(this, inStream, key)

	def delete(key: String): Try[Unit] =
		fs.deleteNode(this.key, key)

	def listBuckets: Try[Iterable[IBucket]] =
		Success(Nil)

	def read(key: String): Try[INode] =
		fs.getNode(this.key, key)

	def path: String =
		this.key
}

case class Node(fs: IFileSystem, parentKey: String, key: String) extends INode {

	def content: Try[InputStream] =
		fs.read(parentKey, key)

	def lastModified: Try[Date] =
		fs.getLastModified(parentKey, key)

	def write(inStream: InputStream): Try[Unit] =
		fs.write(parentKey, inStream, key).map{ _ => Unit }

	def path: String =
		this.key

	def parent =
		fs.getBucket(parentKey)
}

case class SummaryNode(fs: IFileSystem, bucket: IBucket, summary: S3ObjectSummary) extends INode {

	def content: Try[InputStream] =
		fs.read(bucket.key, this.key)

	def key: String =
		summary.getKey()

	def lastModified: Try[Date] = Try {
		summary.getLastModified()
	}

	def write(inStream: InputStream): Try[Unit] =
		fs.write(bucket.key, inStream, key).map{ _ => Unit }

	def path: String =
		fs.resolvePath(bucket.path, this.key)

	def parent = Success(bucket)
}
