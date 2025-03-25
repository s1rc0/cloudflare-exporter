package com.monitoring.cloudflare
package routes

import org.apache.pekko.http.scaladsl.model._
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.Route
import utils.{CloudFlareGraphiQl, CloudFlareApi}
import scala.util.{Success, Failure}
import scala.concurrent.ExecutionContext
import com.typesafe.scalalogging.LazyLogging
import io.circe.syntax._
import io.circe.Json

object Routes extends LazyLogging {
  implicit val ec: ExecutionContext = scala.concurrent.ExecutionContext.global

  import org.apache.pekko.actor.typed.ActorRef
  import actors.DispatcherActor

  private var dispatcherRef: Option[ActorRef[DispatcherActor.Command]] = None
  private var schedulerRef: Option[org.apache.pekko.actor.typed.Scheduler] = None

  def setDispatcher(ref: ActorRef[DispatcherActor.Command])(implicit scheduler: org.apache.pekko.actor.typed.Scheduler): Unit = {
    dispatcherRef = Some(ref)
    schedulerRef = Some(scheduler)
  }

  val routes: Route =
    concat(
      pathSingleSlash {
        get {
          complete(HttpEntity(ContentTypes.`text/plain(UTF-8)`, "Hello, Cloudflare Exporter!"))
        }
      },
      path("metrics") {
        get {
          val startTime = java.time.Instant.now().minusSeconds(36000).toString
          val endTime = java.time.Instant.now().toString

          (schedulerRef, dispatcherRef) match {
            case (Some(scheduler), Some(dispatcher)) =>
              import org.apache.pekko.actor.typed.scaladsl.AskPattern._
              import org.apache.pekko.util.Timeout
              import scala.concurrent.duration._

              implicit val timeout: Timeout = 5.seconds
              implicit val s: org.apache.pekko.actor.typed.Scheduler = scheduler
              val futureZones = dispatcher.ask[List[Map[String, String]]](ref => DispatcherActor.GetZones(ref))
              onComplete(futureZones) {
                case Success(zones) =>
                  if (zones.nonEmpty) {
                  onComplete(CloudFlareGraphiQl.fetchFirewallEventsForZones(zones, startTime, endTime, dispatcher)) {
                      case Success(json) =>
                        val metrics = CloudFlareGraphiQl.convertToPrometheusMetrics(json)
                        complete(HttpEntity(ContentTypes.`text/plain(UTF-8)`, metrics))
                      case Failure(exception) =>
                        logger.error("Failed to fetch aggregated firewall events", exception)
                        complete(HttpResponse(StatusCodes.InternalServerError, entity = "Error fetching metrics"))
                    }
                  } else {
                    complete(HttpResponse(StatusCodes.BadRequest, entity = "No active zones found"))
                  }
                case Failure(exception) =>
                  logger.error("Failed to fetch zones", exception)
                  complete(HttpResponse(StatusCodes.InternalServerError, entity = "Error fetching zones"))
              }
            case (None, _) | (_, None) =>
              complete(HttpResponse(StatusCodes.InternalServerError, entity = "Dispatcher not initialized"))
          }
        }
      },
      path("test") {
        get {
          val startTime = "2025-03-19T16:00:00Z"
          val endTime = "2025-03-19T17:00:00Z"

          onComplete(CloudFlareApi.getZones()) {
            case Success(zones) =>
              logger.info("Fetched zones successfully. Zones: " + zones.mkString(", "))
              if (zones.nonEmpty) {
                onComplete(CloudFlareGraphiQl.fetchFirewallEventsForZones(zones, startTime, endTime, dispatcherRef.get)) {
                  case Success(json) =>
                    complete(HttpEntity(ContentTypes.`application/json`, json.spaces2))
                  case Failure(exception) =>
                    logger.error("Failed to fetch aggregated firewall events", exception)
                    complete(HttpResponse(StatusCodes.InternalServerError, entity = "Error fetching aggregated firewall events"))
                }
              } else {
                complete(HttpResponse(StatusCodes.BadRequest, entity = "No active zones found"))
              }

            case Failure(exception) =>
              logger.error("Failed to fetch zones", exception)
              complete(HttpResponse(StatusCodes.InternalServerError, entity = "Error fetching zones"))
          }
        }
      },
      path("zones") {
        get {
          (schedulerRef, dispatcherRef) match {
            case (Some(scheduler), Some(dispatcher)) =>
              import org.apache.pekko.actor.typed.scaladsl.AskPattern._
              import org.apache.pekko.util.Timeout
              import scala.concurrent.duration._

              implicit val timeout: Timeout = 5.seconds
              implicit val s: org.apache.pekko.actor.typed.Scheduler = scheduler

              val futureZones = dispatcher.ask(ref => DispatcherActor.GetZones(ref, "firewall"))
              onComplete(futureZones) {
                case Success(zones) =>
                  complete(HttpEntity(ContentTypes.`application/json`, zones.asJson.noSpaces))
                case Failure(exception) =>
                  logger.error("Failed to fetch cached zones from dispatcher", exception)
                  complete(HttpResponse(StatusCodes.InternalServerError, entity = "Error fetching cached zones"))
              }
            case _ =>
              complete(HttpResponse(StatusCodes.InternalServerError, entity = "Dispatcher not initialized"))
          }
        }
      },
      path("dispatcher" / "zones") {
        get {
          (schedulerRef, dispatcherRef) match {
            case (Some(scheduler), Some(dispatcher)) =>
              import org.apache.pekko.actor.typed.scaladsl.AskPattern._
              import org.apache.pekko.util.Timeout
              import scala.concurrent.duration._

              implicit val timeout: Timeout = 5.seconds
              implicit val s: org.apache.pekko.actor.typed.Scheduler = scheduler
              val futureZones = dispatcher.ask[List[Map[String, String]]](ref => DispatcherActor.GetZones(ref))
              onComplete(futureZones) {
                case Success(zones) =>
                  val allZones = dispatcher.ask[List[Map[String, String]]](ref => DispatcherActor.GetZones(ref, "all"))
                  onComplete(allZones) {
                    case Success(all) =>
                      val firewallZones = zones
                      val modules = Map("all" -> all, "firewall" -> firewallZones)

                      val statsByModule = modules.map { case (module, zonesList) =>
                        module -> Json.obj(
                          "zoneCount" -> Json.fromInt(zonesList.size),
                          "accountCount" -> Json.fromInt(zonesList.map(_("accountId")).distinct.size)
                        )
                      }

                      val response = io.circe.Json.obj(
                        "statsByModule" -> Json.obj(statsByModule.toSeq: _*),
                        "zonesByModule" -> Json.obj(
                          "all" -> all.asJson,
                          "firewall" -> firewallZones.asJson
                        )
                      )
                      complete(HttpEntity(ContentTypes.`application/json`, response.noSpaces))
                    case Failure(ex) =>
                      logger.error("Failed to fetch all zones from dispatcher", ex)
                      complete(HttpResponse(StatusCodes.InternalServerError, entity = "Error fetching all zones"))
                  }
                case Failure(exception) =>
                  logger.error("Failed to fetch zones from dispatcher", exception)
                  complete(HttpResponse(StatusCodes.InternalServerError, entity = "Error fetching cached zones"))
              }
            case _ =>
              complete(HttpResponse(StatusCodes.InternalServerError, entity = "Dispatcher not initialized"))
          }
        }
      },
      path("dispatcher" / "rules") {
        get {
          (schedulerRef, dispatcherRef) match {
            case (Some(scheduler), Some(dispatcher)) =>
              import org.apache.pekko.actor.typed.scaladsl.AskPattern._
              import org.apache.pekko.util.Timeout
              import scala.concurrent.duration._

              implicit val timeout: Timeout = 5.seconds
              implicit val s: org.apache.pekko.actor.typed.Scheduler = scheduler

              val futureRules = dispatcher.ask(ref => DispatcherActor.GetRules(ref))
              onComplete(futureRules) {
                case Success(rules) =>
                  complete(HttpEntity(ContentTypes.`application/json`, rules.asJson.noSpaces))
                case Failure(exception) =>
                  logger.error("Failed to fetch cached rules from dispatcher", exception)
                  complete(HttpResponse(StatusCodes.InternalServerError, entity = "Error fetching cached rules"))
              }
            case _ =>
              complete(HttpResponse(StatusCodes.InternalServerError, entity = "Dispatcher not initialized"))
          }
        }
      }
    )
}