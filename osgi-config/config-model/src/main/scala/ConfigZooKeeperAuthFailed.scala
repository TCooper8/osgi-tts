package com.cooper.osgi.config

case class ConfigZooKeeperAuthFailed(message: String = null, cause: Throwable = null) extends Exception {
}
