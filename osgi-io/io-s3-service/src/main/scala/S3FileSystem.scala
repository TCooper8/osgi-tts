package com.cooper.osgi.io.s3

import com.amazonaws.services.s3.AmazonS3Client
import com.amazonaws.services.s3.model.ObjectMetadata
import com.cooper.osgi.io.{IBucket, INode, IFileSystem}
import java.io.{File, InputStream}
import java.util.Date
import scala.util.{Success, Failure, Try}
import scala.collection.JavaConversions._
import java.nio.file.Paths

class S3FileSystem(client: AmazonS3Client) extends IFileSystem {
	val separator: String = File.separator

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

	def deleteNode(bucket: String, key: String): Try[Unit] = Try {
		client.deleteObject(bucket, key)
	}

	def getLastModified(bucket: String, path: String): Try[Date] = Try{
		client.getObjectMetadata(bucket, path).getLastModified()
	}

	def getBucket(path: String): Try[IBucket] = Try {
		client.listBuckets.find {
			bucket => bucket.getName == path
		}.map {
			bucket => Bucket(this, client, bucket)
		}.get
	}

	def listBuckets: Try[Iterable[IBucket]] = Try {
		client.listBuckets.map {
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
				client.getObject(bucketName, key).setKey(dst)
				node
			case _ =>
				assert(false, s"$node is invalid INode type for this file system.")
				null
		}
	}

	def read(bucket: IBucket, key: String): Try[InputStream] =
		read(bucket.key, key)

	def read(bucket: String, key: String): Try[InputStream] = Try {
		client.getObject(bucket, key).getObjectContent
	}

	def write(bucket: IBucket, inStream: InputStream, key: String): Try[INode] =
		write(bucket.key, inStream, key)

	def write(bucketName: String, inStream: InputStream, key: String): Try[INode] = {
		Try {
			Option{ client.getObject(bucketName, key) }
		} match {
			case Success(Some(obj)) => Try {
				obj.setObjectContent(inStream)
				Node(this, bucketName, key)
			}
			case Success(None) => Try {
				client.putObject(bucketName, key, inStream, new ObjectMetadata())
				Node(this, bucketName, key)
			}
			case Failure(err) => Failure(err)
		}
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
