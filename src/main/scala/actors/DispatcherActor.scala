package com.monitoring.cloudflare
package actors

import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import com.typesafe.scalalogging.LazyLogging
import scala.concurrent.Future
import org.apache.pekko.util.Timeout
import scala.concurrent.duration._
import scala.util.{Success, Failure}
import utils.CloudFlareApi
import scala.concurrent.ExecutionContext

object DispatcherActor extends LazyLogging {

  // Define messages (commands) the actor can handle
  sealed trait Command
  case object Start extends Command
  final case class GetZones(replyTo: ActorRef[List[Map[String, String]]], module: String = "firewall") extends Command
  final case class RemoveZone(module: String, zoneId: String) extends Command

  def apply(): Behavior[Command] = Behaviors.setup { context =>
    implicit val ec: ExecutionContext = context.executionContext
    implicit val timeout: Timeout = 5.seconds

    var cachedZonesByModule: Map[String, List[Map[String, String]]] = Map.empty

    def refreshZones(): Future[List[Map[String, String]]] = {
      val log = context.log
      CloudFlareApi.getZones().andThen {
        case Success(zones) =>
          log.info(s"✅ Refreshed zones: ${zones.map(_("zoneName")).mkString(", ")}")
        cachedZonesByModule += ("firewall" -> zones)
        cachedZonesByModule += ("all" -> zones)
        case Failure(ex) =>
          log.error("❌ Failed to refresh zones", ex)
      }
    }

    Behaviors.receiveMessage {
      case Start =>
        context.log.info("🚀 DispatcherActor received START command")
        refreshZones()
        Behaviors.same

      case GetZones(replyTo, module) =>
        context.log.info(s"📥 Received GetZones request for module: $module")
        val zones = cachedZonesByModule.getOrElse(module, Nil)
        if (zones.nonEmpty) {
          context.log.info(s"📤 Returning ${zones.size} cached zones for module $module")
          replyTo ! zones
        } else {
          context.log.info(s"📦 No cached zones found for module $module, refreshing from API...")
          context.pipeToSelf(refreshZones()) {
            case Success(_) => GetZones(replyTo, module)
            case Failure(_) => GetZones(replyTo, module)
          }
        }
        Behaviors.same

      case RemoveZone(module, zoneId) =>
        cachedZonesByModule = cachedZonesByModule.updatedWith(module) {
          case Some(zones) => Some(zones.filterNot(_("zoneId") == zoneId))
          case None         => None
        }
        context.log.info(s"🧹 Removed zone $zoneId from module '$module'")
        Behaviors.same
    }
  }
}