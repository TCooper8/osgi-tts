package com.cooper.osgi.config

case class ConfigException(message: String = null, cause: Throwable = null) extends Exception
