package com.cooper.osgi.io.s3

import com.cooper.osgi.io.{INode, IBucket, IFileSystem}
import com.amazonaws.services.s3.AmazonS3Client
import com.amazonaws.services.s3.model.{Bucket => S3Bucket}
import scala.util.Try
import java.util.Date
import java.io.InputStream
import scala.collection.JavaConversions._

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
