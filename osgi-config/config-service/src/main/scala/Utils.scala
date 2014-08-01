package com.cooper.osgi.config.service

import java.util.concurrent.atomic.AtomicReference
import com.cooper.osgi.tracking.{ITracker, ITrackerService}
import org.slf4j.LoggerFactory

object Utils {
	private[this] val trackerService: AtomicReference[ITrackerService] =
		new AtomicReference[ITrackerService](null)

	def getLogger(cls: Any) =
		LoggerFactory.getLogger(cls.getClass)

	def getTracker(name: String): ITracker[String] =
		trackerService.get().getTracker(name)

	def setTrackerService(ref: ITrackerService) {
		Option{ ref } foreach {
			ref => this.trackerService.set(ref)
		}
	}
}
