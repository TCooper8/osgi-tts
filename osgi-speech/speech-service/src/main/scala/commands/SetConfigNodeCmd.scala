package com.cooper.osgi.speech.commands

import org.apache.karaf.shell.commands.{Command, Argument}
import org.apache.karaf.shell.console.AbstractAction
import org.osgi.service.cm.ConfigurationAdmin

@Command(scope = "cooper:speech", name = "set-configNode", description = "Sets the current config node.")
class SetConfigNodeCmd(configAdmin: ConfigurationAdmin, pid: String) extends AbstractAction {

	private[this] val key = "configNode"

	@Argument(index=0, name="configNode", description="The new config node.", required=true, multiValued = false)
	var value: String = null

	override def doExecute(): Object = {
		Constants.doSetCmd(configAdmin, pid)(key, value)
		null
	}
}
