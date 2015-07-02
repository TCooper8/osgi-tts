# Parent of the osgi-projects modules'

## Details

This project is meant to act as a parent for a collection of cooper osgi projects. Giving the build structure a common parent allows for consistent versioning, as well as a simpler build process. 

## Set up

This setup has only been tested on the following.

Apache Maven 3.2.1 - Maven builder
Apache ZooKeeper 3.4.6
Apache Karaf 3.0.0 - OSGi implementation

Java version: 1.7
Os: Mac OSX 10.8.5 - x86_64

- Build using maven.
	
	mvn 'build'|'package'|'install'|'deploy'

	For details on build, package, install, and deploy see your maven builder for details.

- (Eventually) Set up ZooKeeper server under some host: ex(localhost:2181).

	Note: If you do not set up ZooKeeper, a bunch of dead letters might be dumped to the console, this is to avoid the ZooKeeper implementation from completely shutting down its connection pool.

	The config service will expect some ZooKeeper server to eventually become available.

	Note: ZooKeeper should be run on a trusted network, as no authentication is currently built into this module.

	No initial configuration is required by the service.
	No initial ZooKeeper server is required by the service.

	Note: Though ZooKeeper is not mandatory for setup, the service will continuously attempt to connect to the ZooKeeper end point. 

	Please remember to validate the ZooKeeper end point and nodes.

- (Optional) Set up an S3 end point.
	
	The io service can be set to an S3 file system with an end point and valid credentials.
	The io service will default to the local file system, but if S3 is desired this can easily be configured. 

	Make sure that the end point, the S3 access key and S3 secret keys are valid.

- Set up OSGi implementation.

	karaf :> feature:install webconsole http http-whiteboard
		- The http service boots up under port 8181 by default.
		- http-whiteboard is a service that will allow servlets to easily be registered with the http service.
		- The webconsole is under http://host:http-port/system/console
		- Default credentials are username:karaf, pass:karaf

		This is to install all of the web features, and to allow the speech service to connect to the web server.

		This install can be configured on boot up by editing the '/etc/org.apache.karaf.features.cfg' config file. 

		Add 'mvn:com.cooper.osgi/speech-features/version/xml/features' to the 'featuresRepositories' list.
		Add 'webconsole,http,http-whiteboard,speech-service' to the 'featuresBoot' list.

	karaf :> feature:repo-add mvn:com.cooper.osgi/speech-features/version/xml/features
	karaf :> feature:install speech-features

		This will boot up all of required services for the speech service.

	karaf :> bundle:list
		[ This should list all of your bundles, make sure there are no errors. ]

	karaf :> diag 'bundle-id' 
		[ This will allow you to debug the state of the bundle. ]

	karaf :> http:list

		Make sure that the speech servlet is registered under the correct alias, this way you know that the speech servlet linked correctly. 
		- The default alias is /getTTSFile

		wget 'http://localhost:8181/getTTSFile?voice=Crystal&speak=1 2 3'
		- If the TTS engines are not available, some error response should be returned. 

		wget 'http://localhost:8181/getTTSFile?voice=Crystal_spell&speak=1 2 3'
		- If the proxy is running, the static TTS engine should be available.

- Load configuration into ZooKeeper.

	You could manually load in the configuration one key at a time, but that's really tedious. 
	Instead I reccomend using the provided cooper:speech::_ commands.

	cooper:speech:load-config rootNode configPath
		 - Example
		 karaf :> cooper:speech:load-config /machine-local /opt/configs/proxyConfig.cfg

		 The first parameter is the root node to load the configuration file to, use / for the absolute root.

- Testing

	The system itself will constantly run tests to make sure that things are available. 
	All components will run tests on construction to ensure that nothing can fail. 
	Any failure that occurs will be logged and can be reconfigured on runtime. 

	After installing the features and loading the configuration, check the logs to make sure no failure occurred. If failure did occur, such as "Bucket _ does not exist.", be sure to check the configuration. 

	Note: The default configuration will probably raise some errors.

	External tests should be run extensively to ensure that the configuration is wired correctly. 
	This should be heavily tested to tailor a final configuration. 

### Configuration

The setup will load a default configuration into the ZooKeeper instance once it becomes available. 

The configuration root be default to the local host name.
The configuration is structured as follows.
```scala
'localHostName' {
	fileSystemType = "local" <- Expects String of "s3" | "local"
	s3SecretKey = "" <- Expects String, only if s3 is used.
	s3AccessKey = "" <- Expects String, only if s3 is used.
	s3EndPoint = "" <- Expects valid Url, only if s3 is used.

	rootPath = "example" <- Expects a String path to a valid bucket.
	filePrefix = "prefix" <- Expects a String.
	fileSuffix = ".suffix" <- Expects a String.
	crypto = "SHA1" <- Expects a valid MessageDigest instance key.
	writerInstances = n <- Expects an Integer. 
	httpGetTimeout = 8 seconds <- Expects a parsable FiniteDuration.
	cacheSize = 1024 <- Expects an Integer.

	staticEngine {
		fileSystemType = "local" <- Expects String of "s3" | "local"
		s3SecretKey = "" <- Expects String, only if s3 is used.
		s3AccessKey = "" <- Expects String, only if s3 is used.
		s3EndPoint = "" <- Expects valid Url, only if s3 is used.

		voiceNameA {
			filePrefix = "prefix" <- Expects a String.
			fileSuffix = ".suffix" <- Expects a String.
			rootPath = "voiceName" <- Expects a valid bucket path.
		}
		voiceNameB { /* Config */ }
		voiceNameC { /* Config */ }
	}

	engines {
		engineA {
			protocol = "http" <- Expects a valid tcp/ip protocol. 
			host = "hostname" <- Expects a valid host name.
			ports = "8080-8084, 8084, 8085-8090" <- Expects a comma separated list of ranges.
			alias = "getFile" <- Expects a String.
			voices = "voiceA, voiceB, voiceC" <- Expects a comma separated list of Strings.
		}
		engineB { /* Engine configuration */ }
	}
}
```

### Warning

Even though this allows for the usage of dynamic configurations, a fresh restart is highly encouraged after making significant changes. The reason behind this is that when a configuration change occurs, a large amount of code might be generated to represent the new state. When this occurs, the new code base is allocated onto the heap, along with the old code base, effectively doubling the amount of memory consumed. To avoid consuming large amounts of memory, it is advised that the end user restart the instance after a large configuration change. 

## The service

As a whole, the proxy heavily manages multiple layers of functionality. It is split up into, caching, file IO, staticEngine, and TTS Engines.

File IO - The file IO is handled by the IO service. The proxy simply manages the configuration of the IO service.

Caching - Caching is managed by the proxy, which uses LRU implementations to occomplish quick lookup times. There is a configuration key that maps to the maximum size of the LRU map - This value should be sized according to memory and expected response sizes.

Static engine - The static engine is spun up as an internal service, which loads in its own configuration watch and children. The static engine manages its own functionality and is meant to simply spin up voices as they become configured. The static engine itself acts as a lookup table to voices, then if a voice is available it will invoke a translate request to the voice actor. Voice actors follow the specifications of the OSGi speech service.

TTS engines - The TTS engines are managed by an EngineProxy. This proxy will register configured engines to routers that are indexed by voice keys. Meaning that a given engine may be routed to by requesting a voice key to the proxy. The engine proxy is meant to manage multiple connections to a single machine. The second layer of the engine proxy is the actual engine router. The engine router will track multiple connections to a single machine, then route to the connections by a smallest mailbox protocol. 

Cont... - The last layer is an actual Handler. A single engine handler will track a given Url to structure TTS reqeusts. The proxy communicates with the EngineProxy, which routes the messages to the correct router, which will route that message to an available handler. The handler makes the actual request and tracks its own failure. Failure is always returned back up the chain, back to the original sender. 

## Assumptions

This service does very little except connect a collection of services together. It itself makes very few guarantees past its behavior in failover. In effect to assumptions that are made; It makes no assumptions about the underlying systems itself, in fact it expects failure on almost every level of its functionality. 

In the configuration, it does not assume that the nodes and keys are valid, it will validate them before applying the configurations. Log message will be raised on failure.

In calling engines, it does not assume the engines will succeed. There is a timeout on the proxy's request to the engine proxy. Then, there is also timeout management on the individual engines, so that failure can be caught on multiple levels. 

See; osgi-config, osgi-io, osgi-sampled for assumptions that might be made in the underlying system.

## Warnings

There could be places where the configuration has not loaded correctly, in this case simply attempt to push the configuration again. Pushing configuration (key -> value) pairs are cheap - Pushing entirely new configurations may be expensive.




