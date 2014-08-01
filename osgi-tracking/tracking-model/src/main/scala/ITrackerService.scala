package com.cooper.osgi.tracking

trait ITracker[A] {
	def put(k: A, v: Long): Unit

	def get(key: A): Option[Long]

	def iterable: Iterable[(A, Long)]
}

trait ITrackerService {
	def getTracker(name: String): ITracker[String]
}
