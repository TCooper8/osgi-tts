package com.cooper.osgi.utils

import org.slf4j.Logger

/**
 * This is a utility class for logging failure.
 * This class uses Maybe 'a to express failure then maps the result to an Option 'a.
 * 		'a -> Maybe 'a -> Either 'a error -> Option 'a
 *
 * @param log This is used to log failure.
 * @param track This is used to track failures.
 **/
case class MaybeLog(log: Logger, track: (String, Long) => Unit) {
	/**
	 * Internal utility for logging an error.
	 * @param e The error to log.
	 */
	def logErr(e: Throwable) {
		log.error("", e)
		track(e.getClass.getName, 1)
	}

	/**
	 * This uses Maybe to safely map an expression 'a to an Option 'a.
	 * @param expr The expression to evaluate.
	 * @return Returns Some(value) if successful, else None if the expression failed.
	 */
	def apply[A](expr: => A): Option[A] = Maybe(expr) match {
		case Left(v) => Option(v)
		case Right(e) => logErr(e); None
	}
}
