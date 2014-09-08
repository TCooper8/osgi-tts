package com.cooper.osgi.speech.commands

import org.apache.karaf.shell.commands.{Command, Argument}
import org.apache.karaf.shell.console.AbstractAction
import com.cooper.osgi.speech.service.TtsProxyServlet
import scala.util.Failure

@Command(scope = "cooper:speech", name = "load-configFile",
	description = "Attempts to parse the given file and load it into the cooper config service.")
class LoadConfigFile(proxyService: TtsProxyServlet) extends AbstractAction {

	@Argument(index=0, name="rootNode", description="The root node.", required=false, multiValued = false)
	var rootNode: String = ""

	@Argument(index=1, name="configFile", description="The new config file to try and load.", required=true, multiValued = false)
	var filePath: String = ""

	override def doExecute(): Object = {
		val rootNodeIn = if (rootNode == "/") "" else rootNode
		proxyService.configPutFile(rootNodeIn, filePath) match {
			case Failure(err) =>
				err.printStackTrace()
			case _ =>
				println(s"Sucessfully put $filePath")
		}
		null
	}
}
