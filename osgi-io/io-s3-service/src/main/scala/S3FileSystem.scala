package com.cooper.osgi.s3

import com.amazonaws.services.s3.AmazonS3Client
import com.amazonaws.services.s3.model.{Bucket => S3Bucket, ObjectMetadata, S3ObjectSummary, S3Object}
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

case class Bucket(fs: IFileSystem, client: AmazonS3Client, bucket: S3Bucket) extends IBucket {

	def creationDate: Try[Date] = Try {
		bucket.getCreationDate()
	}

	def listNodes: Try[Iterable[INode]] = Try {
		client.listObjects(this.key).getObjectSummaries.map {
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
		fs.listBuckets

	def read(key: String): Try[INode] =
		fs.getNode(this.key, key)

	def path: String =
		this.key
}
