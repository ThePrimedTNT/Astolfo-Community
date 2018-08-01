package xyz.astolfo.astolfocommunity

import net.dv8tion.jda.core.JDA
import net.dv8tion.jda.core.utils.SessionControllerAdapter
import net.dv8tion.jda.core.utils.tuple.Pair

class AstolfoSessionController(private val gatewayUrl: String, private val gatewayDelay: Int) : SessionControllerAdapter() {

    override fun runWorker() {
        synchronized(lock) {
            if (workerHandle == null) {
                workerHandle = QueueWorker(gatewayDelay)
                workerHandle.start()
            }
        }
    }

    override fun getGatewayBot(api: JDA): Pair<String, Int> {
        val gatewayBot = super.getGatewayBot(api)
        return Pair.of(getGateway(api), gatewayBot.right)
    }

    override fun getGateway(api: JDA?): String {
        return gatewayUrl
    }

}