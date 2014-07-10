package com.cooper.osgi.io.s3

import scala.util.{Failure, Success, Try}

object Functional {
	def eitherT[A](expr: => A): Either[A, Throwable] =
		Try{ expr } match {
			case Success(v) => Left(v)
			case Failure(err) => Right(err)
		}
}
