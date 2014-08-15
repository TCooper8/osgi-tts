package com.cooper.osgi.speech.service

import java.io.{OutputStream, InputStream, Closeable}
import org.apache.commons.io.IOUtils
import java.util.concurrent.atomic.AtomicReference
import com.cooper.osgi.tracking.{ITracker, ITrackerService}
import org.slf4j.LoggerFactory

object Utils {
	private[this] val trackerService: AtomicReference[ITrackerService] =
		new AtomicReference[ITrackerService](null)

	def getLogger(name: String) =
		LoggerFactory.getLogger(name)

	def getLogger(cls: Any) =
		LoggerFactory.getLogger(cls.getClass)

	def getTracker(name: String): ITracker[String] =
		trackerService.get().getTracker(name)

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

	def setTrackerService(ref: ITrackerService) {
		Option{ ref } foreach {
			ref =>
				this.trackerService.set(ref)
		}
	}
}
