package com.monitoring.cloudflare
package routes

import org.apache.pekko.http.scaladsl.model._
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.Route

object Routes {
  val routes: Route =
    concat(
      pathSingleSlash {
        get {
          complete(HttpEntity(ContentTypes.`text/plain(UTF-8)`, "Hello, Cloudflare Exporter!"))
        }
      },
      path("metrics") {
        get {
          complete(HttpEntity(ContentTypes.`text/plain(UTF-8)`, "# Prometheus metrics will be exposed here"))
        }
      }
    )
}
