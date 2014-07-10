package com.cooper.osgi.io

import java.io.{OutputStream, InputStream, Closeable}

/**
 * This is a utility object for managing IO operations.
 * 	- These operations should be wrapped in a monad for fault tolerance.
 */
object IOUtils {
	/**
	 * Used to automatically close a resource after function evaluation.
	 * @param resource The resource to use.
	 * @param f The resource consuming function.
	 * @return Returns given f(resource) where f :: resource -> b .
	 */
	def using[A <: Closeable, B](resource: A)(f: A => B) = {
		try f(resource)
		finally resource.close()
	}

	/**
	 * Copies the contents of the input stream to the output stream.
	 * @return Returns the number of bytes transferred.
	 */
	def copy(in: InputStream, out: OutputStream): Int =
		org.apache.commons.io.IOUtils.copy(in, out)
}
