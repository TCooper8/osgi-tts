package com.cooper.osgi.io.local

import com.cooper.osgi.utils.{MaybeLog, StatTracker, Logging}
import java.io.{FileInputStream, File}

/**
 * This represents a utilty object for safely handling files.
 */
object SafeFile {

	private[this] val log = Logging(this.getClass)

	private[this] val track = StatTracker(Constants.trackerKey)

	private[this] val maybe = MaybeLog(log, track)

	/**
	 * Attempts to open a file handle.
	 * @param filename The name of the file.
	 * @return Returns Some(file) if successful, else None.
	 */
	def apply(filename: String) = maybe {
		new File(filename)
	}

	/**
	 * Attempts to open a file handle.
	 * @param parent The parent file.
	 * @param filename The name of the file.
	 * @return Returns Some(file) if successful, else None.
	 */
	def apply(parent: File, filename: String) = maybe {
		new File(parent, filename)
	}

	/**
	 * Attempts to open an input stream to the given file.
	 * @param file The file to read.
	 * @return Returns Some(stream) if successful, else None.
	 */
	def toStream(file: File) = maybe {
		new FileInputStream(file)
	}
}
