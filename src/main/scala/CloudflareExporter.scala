package com.monitoring.cloudflare

import org.apache.pekko
import pekko.actor.typed.ActorSystem
import pekko.actor.typed.scaladsl.Behaviors
import pekko.http.scaladsl.Http
import scala.util.{Failure, Success}
import scala.concurrent.ExecutionContextExecutor
import routes.Routes
import utils.Config
import com.typesafe.scalalogging.LazyLogging

object CloudflareExporter extends LazyLogging {

  def main(args: Array[String]): Unit = {
    Config.validate()

    implicit val system: ActorSystem[Nothing] = ActorSystem(Behaviors.empty, "CloudflareExporterSystem")
    implicit val executionContext: ExecutionContextExecutor = system.executionContext

    import actors.DispatcherActor

    val dispatcher = system.systemActorOf(DispatcherActor(), "dispatcher")
    dispatcher ! DispatcherActor.Start
    routes.Routes.setDispatcher(dispatcher)(system.scheduler)

    val bindingFuture = Http().newServerAt("0.0.0.0", 8080).bind(Routes.routes)

    bindingFuture.onComplete {
      case Success(binding) =>
        logger.info(s"Server online at http://${binding.localAddress.getHostString}:${binding.localAddress.getPort}/")
      case Failure(exception) =>
        logger.error(s"Failed to bind HTTP server: ${exception.getMessage}", exception)
        system.terminate()
    }
  }
}
