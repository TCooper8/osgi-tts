package com.cooper.osgi.io.local

import com.cooper.osgi.io.ILocalFileSystem
import java.io.File

class LocalFileSystem() extends LocalDirNode(new File("/")) with ILocalFileSystem
