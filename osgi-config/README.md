# An OSGi implementation of the com.cooper.osgi.config library

## Overview

This module is meant to be run alongside an apache ZooKeeper server. It acts as a client to watch for updates and make changes to the configuration on the fly. This module does not assume that ZooKeeper is up and running, instead it will constantly check for a ZooKeeper server to become available to the end point that it was configured with. This module relies heavily on akka actors to ensure consistency among children.

## Details

### The service

The config service is exposed via an interface through the OSGi service feature. To understand how the service works, see 'OSGi whiteboard pattern', as well as 'OSGi blueprints' to understand how to easily manage the services - These details can be found around the net ( apache OSGi etc. . .). I discourage the use of BundleActivator(s), because the OSGi implementations seem to varry greatly from one to another, in which case one might treat edge cases differently than another - Such as how to handle service availability. Blueprints can experience the same problems, but they are a safer bet because it is on the writer of the OSGi implementation to get things right.

The config service is a web of running services. For one, the config service has to pool the ZooKeeper connnections to manage any availability problems that it might encounter. The ZooKeeper pool simply manages a bunch of ZooKeeper connections. When a ConfigWatchActor is spun up, it will request a connection from the pool, in which case the pool will attempt to either retrieve the connection, or create one and place it into active service, then return it to the requestor. The connection pool will passively clean up any bad connections that are not in use, as well as handle the state of the connection. 

The IConfigService merely acts as an interface into the actual service logic. It boots up the running actor system, a ZooKeeper connection pool, then awaits the user to invoke the apply method to retrieve a watch, in which case it will map IConfigurable -> defaultData: Map[String, String] -> Try[IConfigWatcher]. Try is used to lift failable expressions to either a Success or a Failure monad for the safe mapping of expressions.

The config proxy - This is booted up to act as a translation layer between the ZooKeeper connection and the config watch actor. The ZooKeeper connection invokes callbacks to the proxy, which are then translated into Actor Messages and sent off to the proxies child actor. 

The config proxy actor - This is used to ensure that all ZooKeeper connection messages are handled in a sequential order, and can be safely handled and sent off to the watch user. Because consistency can be an issue from the ZooKeeper connection, an actor is used. Because availability can also be an issue when using the ZooKeeper connection, the actor uses a ZooKeeper connection pool. The actor also uses a timeout in the event that no connection can be made available, or to keep alive the active connection and debug the state of the server.

### The watch

Watch - This refers to the users implementation of an IConfigurable object that is registered into the config service. As such, the IConfigurable object has a configUpdate method that takes an Iterable[String, String] - Iterable[ Node name -> Node value]. The 'configUpdate' method is used as a callback for the config service to communicate with the watch.

This module uses an IConfigurable interface to communicate back and forth with the client and the Config api. The IConfigurable interface is used to issue updates to the client as an Iterable[String, String] for easy traversal. It is up to the client to watch for invalid config keys or values. The values are encoded and decoded using UTF-8.

Note : A strict IConfigurable interface via named methods from the config api is not possible due to the guarantees made by OSGi - Methods must be exposed through an interface and provided through the whiteboard pattern. Doing so would also eliminate the dynamic model of the config service, as a static configuration would have to be enforced. Enforcing a static methodology is fine in some cases for the watch, but there is no way of enforcing this static model onto ZooKeeper without making further assumptions about the system.

To communicate with the config service, the watch must extend the IConfigurable interface provided by the config model package, then push that IConfigurable object into the OSGi IConfigService service.

Default data can be pushed into the configuration - configService(IConfigurable, Iterable[String, String]). Default data will only be loaded if data does not already exist. If data already exists, the config service will invoke the watches configUpdate(newData iterable).

### The model

The config model embraces an eventually, dynamic, configuration management pattern.

Eventually - Meaning that updates are not guaranteed to happen immediately, but they will occur in the order that the config service retrieved them from the ZooKeeper connection, and it will ensure that a configUpdate will be issued once it has validated the node information. 

Dynamic - Meaning that a configuration of M | M ⊃ Set[String, String], can always be loaded even if M is not a super set of M' where M' is the previous configuration for which M' ⊃ Set[String, String]. It is up to the watch to handle new configuration updates. However, there is currently no way of informing the watch that a node has been explicitly removed from configuration. The intersection of M' and M is lost, where it might otherwise be desired.

Continued - Due to the complexity of managing dynamic configurations, removing nodes on the fly is not supported. Meaning that a watch restart must be issued in the event of removing configuration - Only if the watch does not throw away old configurations entirely when a configUpdate is invoked. From the config service, there is no way of informing the watch that a node has been removed. Internally, the removal of a node appears as a NONODE message, in which case the config service cannot tell if the node is currently unavailable, or has been explicitly removed from service - As a result, there is simply no update issued. From the watch end, a configUpdate simply looks like new configuration has come into scope.

Configuration - A configuration is simply a map of config node names, to node values. Due to the limitation of ZooKeeper, these nodes are untyped. A type cannot be enforced by the ZooKeeper server because it is volatile to any other user on the system. As such, it is up to the watch writer to ensure that valid node names and values are being loaded. The config service does allow for the watch to push data into configuration. If desired, the watch writer could enforce typing by waiting for an update, parsing the data into a correct format and pushing it back to the service. But be warned, if two watches are in conflict over how to enforce typed data, this could spawn an infinite loop, where one watch maps A -> B, then another maps B -> A.

### Assumptions

Atomicity - Atomicity is only guaranteed to the level that ZooKeeper guarantees. Atomicity is granulated to a per-node basis. Meaning that if a configuration is loaded, partial configuration might occur due to the availability of the ZooKeeper server. If failure occurs, logging messages will be issued. If failure occurs to an individual node, the node will be left out of the configUpdate invokation.

Availability - Because availability can only be guaranteed to the level of ZooKeeper, a connection pool has been implemented to manage the connections atop actors. The config watches will idle in a timeout awaiting state until a connection is available if no server is available. In which case, the service will still be readily available, but no server side configurations will be registered. 

Consistency - Consistency can only be guaranteed to the level of the zookeeper api. ZooKeeper issues asynchronous callbacks to a zookeeper Watcher in the event of changes. The watcher must be synchronized in order to ensure that local consistency is guaranteed, but ultimately it is at the mercy of the zookeeper implementation. 

Connection - In order to use this service, a zookeeper host and root node must be provided, it is assumed to be a valid host and node. In the event of an invalid host, the service will not become unavailable, it will spin and wait for the server to become available. In the event that an invalid host has been entered, the watch must be restarted with the new valid host. Error messages are logged accordingly. 

Warning: Due to the nature of allowing default data to be loaded, an otherwise non-existent root node might be created anew due to a typo by the user. The ZooKeeper server should be checked for undesired nodes. All loaded configuration nodes and values are assumed to be valid. It is highly encouraged to validate the data before loading in new data. 

### Warnings

When using the config service, if a valid IConfigWatcher is retrieved from service, be sure to shut it down before throwing it away via the 'dispose' method. Otherwise one might get N-number of watches that are all watching the same nodes, and issuing duplicate updates.

### Features 

The ZooKeeper client has limitations in regards to how data can be inserted into configuration. As such, there is an available feature to the IConfigWatcher that allows the placement of key:String -> data:String values. There is also a putData(rootNode:String, inStream:InputStream) method meant to parse a file structure and place it into service. Currently, the parsing occurs via the typesafe config ConfigFactory parseReader methods. See the 'typesafe config' documentation for details. 

## Usage 

The current usages are implemented heavily throughout the osgi-speech module. 

### Example usage of a static configuration.

trait Msg
case class Letter(msg: String) extends Msg
case class Update(props: Iterable[String, String]) extends Msg

/**
  * The config host is assumed to be valid here, in result, a typo 
  *    could allow for the watch to spin until it gets a valid connection.
  * The config node is also assumed to be valid. In the event that the node
  *    does not exist, the watch will create a new node. 
  **/
case class LetterReader(
		configHost: String,
		configNode: String,
		service: IConfigService
			) extends Actor {

	val kFormat = "format"

	var format = "%s"

	val config = LetterConfig(self)

	val watch = service(
		config, 
		List( config.kFormat -> config.format ) // Loads default data.
	)

	val propHandleMap = Map(
		kFormat -> v: String => 
			this.format = v
	)

	def receive: Receive = {
		case Letter(msg) =>
			log.info(format, msg)  // Example usage.

		case Update(props) =>

			// This can be done in any number of ways.
			props foreach {
				case (k, v) => 
					propHandleMap get k foreach {  _.apply(v) }
			}
	}

	override def configUpdate(props: Iterable[String, String]) = 
		self ! Update(props)  
		// This will allow the configuration to eventually be updated.

	override def postStop() {
		watch foreach { _.dispose() } // This must be disposed of when done.
		super.postStop()
	}
}









