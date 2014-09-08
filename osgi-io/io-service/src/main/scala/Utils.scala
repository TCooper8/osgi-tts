package com.cooper.osgi.io.service

import java.io.{Closeable, OutputStream, InputStream}
import org.apache.commons.io.IOUtils

object Utils {
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

	def using2[A <: Closeable, B <: Closeable, C](resourceA: A, resourceB: B) (f: (A, B) => C) = {
		try f(resourceA, resourceB)
		finally {
			resourceA.close()
			resourceB.close()
		}
	}

	/**
	 * Copies the contents of the input stream to the output stream.
	 * @return Returns the number of bytes transferred.
	 */
	def copy(in: InputStream, out: OutputStream): Int =
		IOUtils.copy(in, out)
}
