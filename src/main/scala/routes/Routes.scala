package com.monitoring.cloudflare
package routes

import org.apache.pekko.http.scaladsl.model._
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.Route
import utils.{CloudFlareGraphiQl, CloudFlareApi}
import scala.util.{Success, Failure}
import scala.concurrent.ExecutionContext
import com.typesafe.scalalogging.LazyLogging
import io.circe.generic.auto._
import io.circe.syntax._

object Routes extends LazyLogging {
  implicit val ec: ExecutionContext = scala.concurrent.ExecutionContext.global

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
      },
      path("test") {
        get {
          val zoneId = "8c9992999dc0c6147066da85d5ce85c7"
          val startTime = "2025-03-19T16:00:00Z"
          val endTime = "2025-03-19T17:00:00Z"

          onComplete(CloudFlareGraphiQl.fetchFirewallEvents(zoneId, startTime, endTime)) {
            case Success(json) =>
              complete(HttpEntity(ContentTypes.`application/json`, json.spaces2))
            case Failure(exception) =>
              logger.error("Failed to fetch firewall events", exception)
              complete(HttpResponse(StatusCodes.InternalServerError, entity = "Error fetching firewall events"))
          }
        }
      },
      path("zones") {
        get {
          onComplete(CloudFlareApi.getZones()) {
            case Success(zones) =>
              complete(HttpEntity(ContentTypes.`application/json`, zones.asJson.noSpaces))
            case Failure(exception) =>
              logger.error("Failed to fetch zones", exception)
              complete(HttpResponse(StatusCodes.InternalServerError, entity = "Error fetching zones"))
          }
        }
      }
    )
}