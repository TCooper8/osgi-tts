# An OSGi implementation of the com.cooper.osgi.io library

## Overview 

This module is meant to act as a common interface for an arbitrary file system. Note, that the act of merging common functionality to a single interface may or may not limit some of functionality of the actual file system. 

Currently, this interface has been implemented for the local file system, and for S3 file systems.

## Set up

This module relies on a parent pom.xml build file. Build using the maven project 'osgi-projects' as the parent only. 

This setup has only been tested on the following.

Apache Maven 3.2.1 - Maven builder
Apache Karaf 3.0.0 - OSGi implementation

Java version: 1.7
Os: Mac OSX 10.8.5 - x86_64

- Build using maven.
	
	maven :> 'build'|'package'|'install'|'deploy'

	For details on build, package, install, and deploy see your maven builder for details.

- Set up S3 end point if S3 file system is desired.
	Note: The s3 credentials must be linked in at runtime using the provided ILocalFileSystem service interface.

- Set up OSGi implementation.

	karaf :> feature:repo-add mvn:com.cooper.osgi/io-features/1.0-SNAPSHOT/xml/features
	karaf :> feature:install io-features

	karaf :> service:list com.cooper.osgi.io.ILocalFileSystem 
		[com.cooper.osgi.io.ILocalFileSystem]
		------------------------------------
		 	osgi.service.blueprint.compname = localFileSystem
		 	service.id = _
		Provided by : 
		 	Cooper::IO::Service (_)
		Used by: 
		 	...

	karaf :> bundle:list
		[ This should list all of your bundles, make sure there are no error. ]

	karaf :> diag 'bundle-id' 
		[ This will allow you to debug the state of the bundle. ]

	Remember that this only boots up the IO service. Any bundle that wishes to use the service must link into it via the provided service manager.

## Details

### The service

The IO service is exposed via an interface through the OSGi service feature. To understand how the service works, see 'OSGi whiteboard pattern', as well as 'OSGi blueprints' to understand how to easily manage the services - These details can be found around the net ( apache OSGi etc. . .). I discourage the use of BundleActivator(s), because the OSGi implementations seem to varry greatly from one to another, in which case one might treat edge cases differently than another - Such as how to handle service availability. Blueprints can experience the same problems, but they are a safer bet because it is on the writer of the OSGi implementation to get things right.

The IO service is fairy straight forward. It simply acts as a gate keeper between the user and the actual file system. The file system service requires no configuration to get running. It's initial state is to run the local file system. From the local file system, other file system implementations may be pulled - Such as Amazon's S3. 

The IO service acts as a monad. In that, the file system is the root of the sequence, which can be mapped to children. The implementation allows one to retrieve a bucket, which can be thought of as a directory. If a bucket cannot be retrieved, the file system allows for the creation of a bucket, which then holds nodes/files. Files can only be accessed by pulling the desired bucket. Buckets cannot contain other buckets. The behavior of nodes and buckets match that of an S3 file system - See Amazon S3 Java API for details. 

### The S3 service

Due to the fact that S3 is a remote file system, it leaves it open to certain failure possibilities. Because of this, it is highly encouraged to keep reference to the local file system, in the event of S3 failure. S3 failure could be temporary, or permanently lost. All S3 requests are lifted to Try expressions to compensate for potential failures.

The S3 documentation clearly states that when retrieving the content of a node, that the stream should be closed as soon as possible. To avoid potential problems, the S3 service actually transfers all of the data to a local stream and closes the network stream. The logic behind this choice is simple, the general case of retrieving the content of any given node, is to pull all of the data. The given streams must be closed by the user to avoid memory leaks, but network streams are managed by the service itself.

### The model

The model of the IO service is meant to match that of an S3 file system. However, swapping between multiple file systems should not require any changes if used correctly. 

Due to the nature of IO. All IO interactions have been lifted to Try expressions within the IO service. If there is any chance of failure, a Failure(err) will be returned to the user, to allow for safe code flow.

### Assumptions

Because user behavior is difficult to anticipate, and because file system folder structure can differ greatly from one to another; It is highly advised that the provided 'resolvePath' is used whenever attempting to build a reference path for the file system. This is to avoid any errors when creating buckets, or referencing files from the system. The current file systems do assume that any given path is a valid one, no correction will be attempted.

This service does assume that the given network configuration is correct. If the given credentials are incorrect, the operation to pull any given file system will fail, and the result must be handled by the user.

### Warnings

Avoid structuring a custom reference path, instead use the provided 'resolvePath' method attached to the file systems. This is to ensure that a consistent structure is used.

Avoid high contention when creating, retrieving, or deleting buckets. Retrieve or create a bucket, and use it for as long as possible. 

## Usage

The usage is fairly simple. Get the file system, pull a bucket, then manipulate nodes as needed.
Remember to keep hold of the bucket, avoid high contention when creating or manipulating buckets.

## Limitations

ContentTypes - Due to the fact that the local file system has no way of managing programmatic content types, the io service does not provide content types. This is mentioned because S3 does support content types. Currently, the way that content types are managed is by a file suffix, this is managed by the user, not by the service. 

Locking - The local java file APIs offer locking capabilities for files, this is not available through the io service. Due to the complex nature of distributed systems, this service would be much more complex if it provided locking services, instead this service depends on atomic operations. This service does not depend on availability, if things are unavailable then the interface will return Failure(throwable) results.  

Availability - This service does not guarantee availablility to the file system. Instead it assumes that the end point and credentials are valid, so that the user may hit the interface with requests and handle failure accordingly. 

## Guarantees

This service guarantees atomic operations with the file system. All failable expressions are lifted to Try expressions to ensure that program flow does not break. If a node is read from a bucket, the IO service will ensure that the entire object is read before returning the result to the user. Another example might be reading the content from a node - In this case, the entirety of the content will be read and a byte stream will be constructed and returned to the user to ensure that no network issues will arrise after the function has returned. 

### Example
```scala
// Get the service.
val fs: ILocalFileSystem

// Pull a bucket from the service.
val bucket = fs.getBucket("example")
bucket match {
	case Failure(err) => 
		log.error("Cannot retrieve bucket", err)
	case _ => ()
}

val filePath = fs.resolvePath("parent", "childFile.txt")

bucket flatMap {
	_.read(filePath) flatMap {
		_.content flatMap {
			IOUtils.readFully
		}
	}
} match {
	case Failure(err) =>
		log.error("Unable to read file contents", err)
	case Success(res) =>
		log.info(new String(res, "UTF-8"))
}



// To link into the S3 file system.

val accessKey: String = ""
val secretKey: String = ""
val s3EndPoint: String = ""

val s3fs = fs.getS3(accessKey, secretKey, s3EndPoint)

// In the event of failure, keep a handle on the local file system.
```












