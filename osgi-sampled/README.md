# And OSGi implementation of com.cooper.osgi.sampled library.

## Overview

This module is meant to act as a handler for any audio parsing, streaming, or manipulation. It is meant to replace the java.javax.sampled library. As of this writing, the javax sampled library is not OSGi ready, so this library was created to mitigate that shortcoming. Along with describing the mapping of an InputStream to IAudioReader, this library also describes the behavior of mapping multiple InputStream(s) to a single IAudioReader. 

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

- Set up OSGi implementation.

	karaf :> feature:repo-add mvn:com.cooper.osgi/sampled-features/1.0-SNAPSHOT/xml/features
	karaf :> feature:install sampled-features

	karaf :> service:list com.cooper.osgi.sampled.IAudioSystem 
		[com.cooper.osgi.sampled.IAudioSystem]
		------------------------------------
		 	osgi.service.blueprint.compname = audioSystem
		 	service.id = _
		Provided by : 
		 	***REMOVED***::Sampled::Service (_)
		Used by: 
		 	...

	karaf :> bundle:list
		[ This should list all of your bundles, make sure there are no error. ]

	karaf :> diag 'bundle-id' 
		[ This will allow you to debug the state of the bundle. ]

	Remember that this only boots up the sampled service. Any bundle that wishes to use the service must link into it via the provided service manager.

## Details

As of this writing, these are the only available audio parsing specifications implemented.
	- WAVE

### The service

The AudioSystem is meant to act as a map of String -> IAudioReader where an IAudioReader is actually the zero-state of a monad that can describe mapping InputStream(s) to IAudioReader. 

The keys of the map are meant to be accessed at runtime, because other IAudioReader implementations may be loaded at runtime by other bundles. Of course any built in IAudioReader implementations will be provided. 

This service is meant to extend the javax sampled library if it ever becomes OSGi ready, or if a work around is found. This interface is meant to allow for safe audio stream parsing. All failable expressions are lifted to Try expressions, for easy error handling. 

### The model

This module is modelled after a monadic audio parsing stream. It is monadic, in that the zero-state reader is actually just an empty body stream, with a valid header for the audio type it represents. InputStreams can be bound to the reader in any order, which will be mapped an IAudioReader of the same category as the reader the stream was applied to. 

### Assumptions

The currently implemented readers assume that partial failure is acceptable when chaining multiple streams together. The only way to currently check if failure has occurred is to check the 'chainLength' property from the head-most reader. This is not a big issue, because file system nodes that have streams that cannot be accessed will fail to load, thus not making it to the reader for streaming. The only place that a stream can fail is while being parsed. 

Readers can be applied one at a time in a functional fold fashion, this will allow for immediate failure to be caught. Only when binding a reader to a list of streams is when partial failure may occur. 

### Warnings

The current implementation of the audio readers allows for lazy streaming. Meaning that each stream that is passed in, is only read up to the length of the header, the rest of the data is left inside the child stream until a later time. This allows for multiple streams to be quickly streamed together, because only the header is immediately evaluated. Only once the reader's 'copyTo' method is invoked with an output stream, is when the rest of the streams are consumed and sequentially closed. Also note, that the header data from the streams are each validated to the same format, and streamed together to ensure that a single valid WAVE data stream is returned. 

When chaining streams together, do not attempt to use the streams after they have already been read from. Also, do you not attempt to consume a reader from the middle of the chain, then at a later time attempt to consume the head of the chain. The internal state of the monadic reader is just an InputStream waiting to be consumed. 

## Planned features

Multi-reader optimized IAudioReader's are planned, as to avoid constructing the entire chain of readers each time a sequence is desired. Currently, if caching is desired the user must copy the reader to an output stream and copy the buffer. 

## Usage

### Example
```scala
val audioSystem: IAudioSystem = _

val reader = audioSystem get ".wav"

reader match {
	case Failure(err) => log.error("Unable to load audio reader.", err)
	case _ => ()
}

val bucket: AudioFileFolder = _
val streams = bucket listNodes flatMap { _.content }

// This will allow for partial failure of parsing the streams. 
val maybePartial = reader(streams)

// This will ensure that every stream is valid.
val passOrFailed = reader.foldLeft(reader){ (R, is) => R flatMap { _(is) }}

// Note: These resulting readers are actually holding streams.length input streams behind the scenes.
// Make sure that these resulting readers are eventually closed. 
// Calling the 'copyTo' method from the reader will consume all of the streams and sequentially close them.
```





