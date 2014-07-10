package com.cooper.osgi.utils

/**
 * This represents a utility for evaluating fail-able expressions safely.
 */
object Maybe {
	/**
	 * Lifts a fail-able expression of type 'a to an Either 'a, error.
	 * 	- This method should be used to prevent failure.
	 *
	 * @param a The expression to evaluate.
	 * @return Returns a Left(value) if successful. If failure has occurred, Right(error) will be returned.
	 */
	@inline def apply[A](a: => A): Either[A, Throwable] = {
		try Left(a) catch {
			case e: Throwable => Right(e)
		}
	}
}
