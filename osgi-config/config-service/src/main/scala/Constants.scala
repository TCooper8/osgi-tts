package com.cooper.osgi.config.service

import scala.concurrent.duration._
import akka.util.Timeout

object Constants {
	val trackerKey = "Cooper"
	val keeperTickTime = 20 seconds
	val futureTimeout = 10 seconds
	implicit val askTimeout = Timeout(futureTimeout)
}
