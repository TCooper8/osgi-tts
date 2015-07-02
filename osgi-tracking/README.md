# An OSGi implementation of the com.cooper.osgi.tracking library

## Overview

This module is meant to track data as a map of String -> Long. 

## Set up

See osgi-projects for details.

## Details

### The service

The tracker service is meant to be an extremely lightweight service for tracking data.
The implementation of the ITracker is just a thread safe map that will add the given Long parameter to the existing value. This is for easily tracking data or gathering statistics. 

It is intended that this service will be used as a lightweight logger. Where strings can be mapped to long values. This can be used to track how many HTTP OK responses were sent. How many GET requests were made, or how many file writes have occurred. 

### The model

The model is just a map of String -> Long.

## Usage

### Example
```scala
case class Service(key: String, trackerService: ITrackerService) {
	val tracker = trackerService.getTracker(key)

	def receive = {
		case GetMsg(query) => tracker.put("GetMsg", 1)
		case PutMsg(query) => tracker.put("PutMsg", 1)
		case ErrorMsg(err) => tracker.put(err.getClass.getName, 1)
		case _ => tracker.put("UnknownMsg", 1)
	}
}
```
