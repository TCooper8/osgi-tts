package com.cooper.osgi.utils

import org.slf4j.LoggerFactory

/**
 * This object is used to retrieve an available slf4j logger.
 */
object Logging {
	def apply(cls: Any) = LoggerFactory.getLogger(cls.getClass)
	def apply(cls: Class[_]) = LoggerFactory.getLogger(cls)
	def apply(name: String) = LoggerFactory.getLogger(name)
}
