package com.cooper.osgi.speech.commands

import org.apache.karaf.shell.commands.{Argument, Command}
import org.apache.karaf.shell.console.AbstractAction
import com.cooper.osgi.speech.service.{Utils => ServiceUtils, Constants => ServiceConstants}

@Command(scope = "cooper:speech", name = "get-track", description = "Gets the current config node.")
class GetTrackingCmd() extends AbstractAction {

	val track = ServiceUtils.getTracker(ServiceConstants.trackerKey)

	@Argument(index=0, name="key", description="The key to query", required=false, multiValued = false)
	var key: String = null

	override def doExecute(): Object = {
		if (key == null) {
			track.iterable.toList.sorted.foreach {
				println
			}
		}
		else {
			println(track.get(key))
		}
		null
	}
}
