package com.cooper.osgi.speech.commands

import org.apache.karaf.shell.commands.Command
import org.apache.karaf.shell.console.AbstractAction
import org.osgi.service.cm.ConfigurationAdmin

@Command(scope = "cooper:speech", name = "get-configNode", description = "Gets the current config node.")
class GetConfigNodeCmd(configAdmin: ConfigurationAdmin, pid: String) extends AbstractAction {

	private[this] val key = "configNode"

	override def doExecute(): Object = {
		Constants.doGetCmd(configAdmin, pid)(key)
		null
	}
}
