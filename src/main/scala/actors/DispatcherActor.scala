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
import io.circe.syntax._

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
      def fetchAllRules(zoneId: String): Future[Unit] = {
        val ruleFutures = Seq(
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
          CloudFlareApi.getRulesets(zoneId).flatMap { rulesets =>
            val existing = cachedFwRulesByZone.getOrElse(zoneId, Map.empty)
            cachedFwRulesByZone += (zoneId -> (existing + ("rulesets" -> rulesets)))
            logger.info(s"✅ Cached ${rulesets.size} rulesets for zone $zoneId")

            val rulesetRulesFutures = rulesets.flatMap(_.get("phase").flatMap(_.asString)).map { phase =>
              CloudFlareApi.getRulesetRules(zoneId, phase).map { rules =>
                val updated = cachedFwRulesByZone.getOrElse(zoneId, Map.empty) + (s"ruleset_rules_$phase" -> rules)
                cachedFwRulesByZone += (zoneId -> updated)
                logger.info(s"✅ Cached ${rules.size} ruleset rules for phase $phase in zone $zoneId")
              }
            }

            Future.sequence(rulesetRulesFutures).map(_ => ())
          }
        )

        Future.sequence(ruleFutures).map(_ => ())
      }

      val zonesFuture = customZoneIdsOpt match {
        case Some(zonesStr) if zonesStr.nonEmpty =>
          val zonePairs = zonesStr.split(",").flatMap(_.split(":") match {
            case Array(name, id) => Some(Map("accountId" -> "", "accountName" -> "", "zoneId" -> id, "zoneName" -> name))
            case _ => None
          }).toList
          cachedZonesByModule += ("firewall" -> zonePairs, "all" -> zonePairs)
          Future.sequence(zonePairs.map(z => fetchAllRules(z("zoneId")))).map(_ => zonePairs)

        case _ =>
          CloudFlareApi.getZones().flatMap { zones =>
            cachedZonesByModule += ("firewall" -> zones, "all" -> zones)
            Future.sequence(zones.map(z => fetchAllRules(z("zoneId")))).map(_ => zones)
          }
      }

      zonesFuture
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
            import io.circe.generic.auto._
            import io.circe.syntax._

            val jsonSafeRules = cachedFwRulesByZone.map { case (zoneId, modules) =>
              zoneId -> modules.map { case (module, rules) =>
                module -> rules.map(_.asJson)
              }
            }
            logger.info(s"📦 Raw cachedFwRulesByZone JSON:\n${jsonSafeRules.asJson.noSpaces}")
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