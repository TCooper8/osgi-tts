package com.cooper.osgi.io

import scala.util.Try

/**
 * An interface type to uniquely represent a local file system implementation.
 */
trait ILocalFileSystem extends IFileSystem {
	def getS3(accessKey: String, secretKey: String, endPoint: String): Try[IFileSystem]
}
