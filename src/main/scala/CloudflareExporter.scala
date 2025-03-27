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

    import org.apache.pekko.actor.typed.scaladsl.AskPattern._
    import org.apache.pekko.actor.typed.scaladsl.AskPattern.schedulerFromActorSystem
    import org.apache.pekko.util.Timeout
    import scala.concurrent.duration._

    val dispatcher = system.systemActorOf(DispatcherActor(), "dispatcher")
    implicit val timeout: Timeout = 60.seconds

    import scala.concurrent.Await

    try {
      val result = Await.result(dispatcher.ask[List[Map[String, String]]](ref => DispatcherActor.Start(ref)), 5.minutes)
      system.log.info("✅ Initialization finished. Starting HTTP server.")
      Routes.setDispatcher(dispatcher)(schedulerFromActorSystem(system))
      val bindingFuture = Http().newServerAt("0.0.0.0", 8080).bind(Routes.routes)

      bindingFuture.onComplete {
        case Success(binding) =>
          logger.info(s"Server online at http://${binding.localAddress.getHostString}:${binding.localAddress.getPort}/")
        case Failure(exception) =>
          logger.error(s"Failed to bind HTTP server: ${exception.getMessage}", exception)
          system.terminate()
      }
    } catch {
      case ex: Throwable =>
        logger.error(s"Startup initialization failed: ${ex.getMessage}", ex)
        system.terminate()
    }
  }
}
