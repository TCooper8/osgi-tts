# An OSGi implementation of the com.cooper.osgi.speech service

## Overview

This module is meant to act as a service to translate text to speech requests. There are two types of speech requests currently supported; speech synthesis requests, and static translation requests. 

Static translations - These translations attempt to take already synthesized speech data and merge the pieces together into a single seamless stream. 

Synthesis translations - These translations utilize text-to-speech engines to create a synthesized audio stream. 

## Details

This is a very large module that implements multiple coordinated pieces into the runtime. 

### Dependencies

This module requires the following osgi-projects' modules.

osgi-config : [model; features]
osgi-io : [model; features]
osgi-sampled : [model; features]
osgi-tracking : [model; features]

### The service

This service offers an HTTP servlet injection into an OSGi HTTP service. The servlet alias can be configured using an OSGi config service. The speech service also provides commands to change the configuration values, via cooper:speech:* through the console. 

The service itself is a proxy that can take HTTP GET requests with a required query. The query consists of a voice and a speak parameter - Ex: ?voice=Crystal&speak=1 2 3. If the voice ends with a "_spell" then the proxy will translate the speech request into a static one. If the requested voice is not available, the response will be a BadRequest. If any engine is unable to translate the request then some failure will be returned. 

Static request - If a static request is made, the proxy will structure a request to the active static engine. If the request cannot be fullfilled within an allotted time period, the translation will fail. Static translations utilize an available file system to perform the translations, if for some reason the file system is not available then the response will be some failure. 

Synth request - If a non-static TTS request is made, the proxy will structure a request to an active TTS engine proxy that will route the request to a valid TTS engine. All routing follows a smallest mailbox protocol. 
