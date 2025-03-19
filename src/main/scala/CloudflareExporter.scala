package com.monitoring.cloudflare

import org.apache.pekko
import pekko.actor.typed.ActorSystem
import pekko.actor.typed.scaladsl.Behaviors
import pekko.http.scaladsl.Http
import pekko.http.scaladsl.model._
import pekko.http.scaladsl.server.Directives._
import pekko.http.scaladsl.server.Route

import scala.util.{Failure, Success}
import scala.concurrent.ExecutionContextExecutor

object CloudflareExporter {

  def main(args: Array[String]): Unit = {
    implicit val system: ActorSystem[Nothing] = ActorSystem(Behaviors.empty, "CloudflareExporterSystem")
    implicit val executionContext: ExecutionContextExecutor = system.executionContext

    val route: Route =
      pathSingleSlash {
        get {
          complete(HttpEntity(ContentTypes.`text/plain(UTF-8)`, "Hello, Cloudflare Exporter!"))
        }
      }

    val bindingFuture = Http().newServerAt("0.0.0.0", 8080).bind(route)

    bindingFuture.onComplete {
      case Success(binding) =>
        println(s"Server online at http://${binding.localAddress.getHostString}:${binding.localAddress.getPort}/")
      case Failure(exception) =>
        println(s"Failed to bind HTTP server: ${exception.getMessage}")
        system.terminate()
    }
  }
}
