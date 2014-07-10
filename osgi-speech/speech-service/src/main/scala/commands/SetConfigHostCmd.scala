package com.cooper.osgi.speech.commands

import org.apache.karaf.shell.commands.{Command, Argument}
import org.apache.karaf.shell.console.AbstractAction
import org.osgi.service.cm.ConfigurationAdmin

@Command(scope = "cooper:speech", name = "set-configHost", description = "Sets the current config host.")
class SetConfigHostCmd(configAdmin: ConfigurationAdmin, pid: String) extends AbstractAction {

	private[this] val key = "configHost"

	@Argument(index=0, name="configHost", description="The new config host.", required=true, multiValued = false)
	var value: String = null

	override def doExecute(): Object = {
		Constants.doSetCmd(configAdmin, pid)(key, value)
		null
	}
}
