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
  final case class Start(replyTo: ActorRef[List[Map[String, String]]]) extends Command

  final case class GetRules(replyTo: ActorRef[Map[String, Map[String, List[Map[String, Json]]]]]) extends Command
  final case class GetZones(replyTo: ActorRef[List[Map[String, String]]], module: String = "firewall") extends Command
  final case class RemoveZone(module: String, zoneId: String) extends Command

  def apply(): Behavior[Command] = Behaviors.setup { context =>
    implicit val ec: ExecutionContext = context.executionContext
    implicit val timeout: Timeout = 5.seconds

    var cachedZonesByModule: Map[String, List[Map[String, String]]] = Map.empty
    var cachedFwRulesByZone: Map[String, Map[String, List[Map[String, Json]]]] = Map.empty

    def refreshZones(accountIds: Set[String], customZoneIdsOpt: Option[String]): Future[List[Map[String, String]]] = {
      val ruleFetchFutures = scala.collection.mutable.ListBuffer[Future[Unit]]()

      def fetchAllRules(zoneId: String): Unit = {
        ruleFetchFutures ++= Seq(
          CloudFlareApi.getRules(zoneId).map { rules =>
            val existing = cachedFwRulesByZone.getOrElse(zoneId, Map.empty)
            cachedFwRulesByZone += (zoneId -> (existing + ("customRules" -> rules)))
            logger.info(s"✅ Cached ${rules.size} firewall rules for zone $zoneId")
          },
          CloudFlareApi.getRateLimitRules(zoneId).map { rules =>
            val converted = rules.map(_.map { case (k, v) => (k, Json.fromString(v)) })
            val existing = cachedFwRulesByZone.getOrElse(zoneId, Map.empty)
            cachedFwRulesByZone += (zoneId -> (existing + ("rate_limit" -> converted)))
            logger.info(s"✅ Cached ${rules.size} rate limit rules for zone $zoneId")
          },
          CloudFlareApi.getUserAgentRules(zoneId).map { rules =>
            val existing = cachedFwRulesByZone.getOrElse(zoneId, Map.empty)
            cachedFwRulesByZone += (zoneId -> (existing + ("ua_rules" -> rules)))
            logger.info(s"✅ Cached ${rules.size} UA rules for zone $zoneId")
          },
          CloudFlareApi.getIpAccessRules(zoneId).map { rules =>
            val existing = cachedFwRulesByZone.getOrElse(zoneId, Map.empty)
            cachedFwRulesByZone += (zoneId -> (existing + ("ip_rules" -> rules)))
            logger.info(s"✅ Cached ${rules.size} IP access rules for zone $zoneId")
          },
          CloudFlareApi.getRulesets(zoneId).map { rules =>
            val existing = cachedFwRulesByZone.getOrElse(zoneId, Map.empty)
            cachedFwRulesByZone += (zoneId -> (existing + ("rulesets" -> rules)))
            logger.info(s"✅ Cached ${rules.size} rulesets for zone $zoneId")
          }
        ).map(_.recover { case ex => logger.error(s"❌ Failed to fetch some rules for $zoneId", ex) })
      }

      val zonesFuture: Future[List[Map[String, String]]] = customZoneIdsOpt match {
        case Some(zonesStr) if zonesStr.nonEmpty =>
          val zonePairs = zonesStr.split(",").flatMap(_.split(":") match {
            case Array(name, id) => Some(Map("accountId" -> "", "accountName" -> "", "zoneId" -> id, "zoneName" -> name))
            case _ => None
          }).toList
          cachedZonesByModule += ("firewall" -> zonePairs, "all" -> zonePairs)
          zonePairs.foreach(zone => fetchAllRules(zone("zoneId")))
          Future.successful(zonePairs)

        case _ =>
          CloudFlareApi.getZones().map { zones =>
            cachedZonesByModule += ("firewall" -> zones, "all" -> zones)
            zones.foreach(zone => fetchAllRules(zone("zoneId")))
            zones
          }
      }

      zonesFuture.flatMap(zs => Future.sequence(ruleFetchFutures.toList).map(_ => zs))
    }

    Behaviors.receiveMessage {
      case Start(replyTo) =>
        context.log.info("🚀 DispatcherActor received START command")
        refreshZones(Config.accountIds, Config.customZoneIds).onComplete {
          case Success(_) =>
            logger.info(s"📦 Final cachedFwRulesByZone content:\n" + cachedFwRulesByZone.map {
              case (zoneId, modules) =>
                s"$zoneId:\n" + modules.map {
                  case (module, rulesList) => s"  $module -> ${rulesList.size} rules"
                }.mkString("\n")
            }.mkString("\n\n"))
            replyTo ! cachedZonesByModule.getOrElse("firewall", Nil)
          case Failure(ex) =>
            context.log.error("❌ Initialization failed during Start", ex)
            replyTo ! cachedZonesByModule.getOrElse("firewall", Nil)
        }
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