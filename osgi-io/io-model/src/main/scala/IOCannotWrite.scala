package com.cooper.osgi.io

import java.io.IOException

case class IOCannotWrite(message: String = null, cause: Throwable = null) extends IOException {
}
