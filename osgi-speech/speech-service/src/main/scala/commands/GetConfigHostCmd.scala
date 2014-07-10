package com.cooper.osgi.speech.commands

import org.apache.karaf.shell.commands.Command
import org.apache.karaf.shell.console.AbstractAction
import org.osgi.service.cm.ConfigurationAdmin

@Command(scope = "cooper:speech", name = "get-configHost", description = "Gets the current config host.")
class GetConfigHostCmd(configAdmin: ConfigurationAdmin, pid: String) extends AbstractAction {

	private[this] val key = "configHost"

	override def doExecute() = {
		Constants.doGetCmd(configAdmin, pid)(key)
		null
	}
}
