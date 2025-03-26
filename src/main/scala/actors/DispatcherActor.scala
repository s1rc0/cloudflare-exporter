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
import utils.Config
import scala.concurrent.ExecutionContext
import io.circe.Json

object DispatcherActor extends LazyLogging {

  // Define messages (commands) the actor can handle
  sealed trait Command
  case object Start extends Command

  final case class GetRules(replyTo: ActorRef[Map[String, Map[String, List[Map[String, Json]]]]]) extends Command
  final case class GetZones(replyTo: ActorRef[List[Map[String, String]]], module: String = "firewall") extends Command
  final case class RemoveZone(module: String, zoneId: String) extends Command

  def apply(): Behavior[Command] = Behaviors.setup { context =>
    implicit val ec: ExecutionContext = context.executionContext
    implicit val timeout: Timeout = 5.seconds

    var cachedZonesByModule: Map[String, List[Map[String, String]]] = Map.empty
    var cachedFwRulesByZone: Map[String, Map[String, List[Map[String, Json]]]] = Map.empty

    def refreshZones(accountIds: Set[String], customZoneIdsOpt: Option[String]): Future[List[Map[String, String]]] = {
      customZoneIdsOpt match {
        case Some(zonesStr) if zonesStr.nonEmpty =>
          val zonePairs = zonesStr.split(",").flatMap(_.split(":") match {
            case Array(name, id) =>
              Some(Map("accountId" -> "", "accountName" -> "", "zoneId" -> id, "zoneName" -> name))
            case _ => None
          }).toList

          cachedZonesByModule += ("all" -> zonePairs, "firewall" -> zonePairs)

          zonePairs.foreach { zone =>
            val zoneId = zone("zoneId")
            CloudFlareApi.getRules(zoneId).onComplete {
              case Success(rules) =>
                val existing = cachedFwRulesByZone.getOrElse(zoneId, Map.empty)
                cachedFwRulesByZone += (zoneId -> (existing + ("customRules" -> rules)))
                logger.info(s"✅ Cached ${rules.size} firewall rules for zone $zoneId")
              case Failure(ex) =>
                logger.error(s"❌ Failed to fetch firewall rules for zone $zoneId", ex)
            }
            CloudFlareApi.getRateLimitRules(zoneId).onComplete {
              case Success(rules) =>
                val existing = cachedFwRulesByZone.getOrElse(zoneId, Map.empty)
                val convertedJson = rules.map(_.map { case (k, v) => (k, Json.fromString(v)) })
                cachedFwRulesByZone += (zoneId -> (existing + ("rate_limit" -> convertedJson)))
                logger.info(s"✅ Cached ${rules.size} rate limit rules for zone $zoneId")
              case Failure(ex) =>
                logger.error(s"❌ Failed to fetch rate limit rules for zone $zoneId", ex)
            }
          }
          Future.successful(zonePairs)

        case _ if accountIds.nonEmpty =>
          CloudFlareApi.getZones().andThen {
            case Success(zones) =>
              logger.info(s"✅ Refreshed zones: ${zones.map(_("zoneName")).mkString(", ")}")
              cachedZonesByModule += ("firewall" -> zones, "all" -> zones)

              zones.foreach { zone =>
                val zoneId = zone("zoneId")
                CloudFlareApi.getRules(zoneId).onComplete {
                  case Success(rules) =>
                    val existing = cachedFwRulesByZone.getOrElse(zoneId, Map.empty)
                    val converted = rules.map(_.map { case (k, v) => (k, Json.fromString(v.toString)) })
                    cachedFwRulesByZone += (zoneId -> (existing + ("customRules" -> converted)))
                    logger.info(s"✅ Cached ${rules.size} firewall rules for zone $zoneId")
                  case Failure(ex) =>
                    logger.error(s"❌ Failed to fetch firewall rules for zone $zoneId", ex)
                }

                CloudFlareApi.getRateLimitRules(zoneId).onComplete {
                  case Success(rules) =>
                    val existing = cachedFwRulesByZone.getOrElse(zoneId, Map.empty)
                    val convertedJson = rules.map(_.map { case (k, v) => (k, Json.fromString(v)) })
                    cachedFwRulesByZone += (zoneId -> (existing + ("rate_limit" -> convertedJson)))
                    logger.info(s"✅ Cached ${rules.size} rate limit rules for zone $zoneId")
                  case Failure(ex) =>
                    logger.error(s"❌ Failed to fetch rate limit rules for zone $zoneId", ex)
                }
              }
            case Failure(ex) =>
              logger.error("❌ Failed to refresh zones", ex)
          }
      }
    }

    Behaviors.receiveMessage {
      case Start =>
        context.log.info("🚀 DispatcherActor received START command")
        refreshZones(Config.accountIds, Config.customZoneIds)
        Behaviors.same

      case GetZones(replyTo, module) =>
        context.log.info(s"📥 Received GetZones request for module: $module")
        val zones = cachedZonesByModule.getOrElse(module, Nil)
        if (zones.nonEmpty) {
          context.log.info(s"📤 Returning ${zones.size} cached zones for module $module")
          replyTo ! zones
        } else {
          context.log.info(s"📦 No cached zones found for module $module, refreshing from API...")
          context.pipeToSelf(refreshZones(Config.accountIds, Config.customZoneIds)) {
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

      case GetRules(replyTo) =>
        replyTo ! cachedFwRulesByZone
        Behaviors.same
    }
  }
}