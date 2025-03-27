package com.monitoring.cloudflare
package utils
import actors.DispatcherActor
import org.apache.pekko.actor.typed.ActorRef

import sttp.client3._
import io.circe.Json
import io.circe.parser._
import io.circe.syntax._
import scala.concurrent.{Future, ExecutionContext}
import com.typesafe.scalalogging.LazyLogging

object CloudFlareGraphiQl extends LazyLogging {

  Config.validate()

  implicit val backend: SttpBackend[Identity, Any] = HttpURLConnectionBackend()

  def fetchFirewallEventsForZones(
                                   zones: List[Map[String, String]],
                                   startTime: String,
                                   endTime: String,
                                   dispatcher: ActorRef[DispatcherActor.Command]
                                 )(implicit ec: ExecutionContext): Future[Json] = {
    val validZones = zones

    val futureResponses = Future.sequence(validZones.map { zone =>
      fetchFirewallEvents(zone, startTime, endTime).map { json =>
        val zoneId = zone("zoneId")
        val zoneName = zone.getOrElse("zoneName", "unknown")
        val cursor = json.hcursor
        val dataField = cursor.downField("data")
        val errorsField = cursor.downField("errors")

        val isErrorResponse = errorsField.focus.exists(_.isArray)
        val hasOnlyErrors = isErrorResponse && dataField.focus.exists(_.isNull)

        val errorCodes = errorsField.as[Vector[Json]].getOrElse(Vector.empty)
          .flatMap(_.hcursor.downField("extensions").get[String]("code").toOption)

        if (hasOnlyErrors) {
          if (errorCodes.contains("authz")) {
            logger.debug(s"❌ Zone $zoneId ($zoneName) returned authz error, removing from module 'firewall'")
            dispatcher ! com.monitoring.cloudflare.actors.DispatcherActor.RemoveZone("firewall", zoneId)
          } else if (errorCodes.contains("budget")) {
            logger.error(s"⏳ Zone $zoneId ($zoneName) hit rate limit (budget), will retry later")
          } else {
            logger.error(s"⚠️ Zone $zoneId ($zoneName) returned unknown error codes: ${errorCodes.mkString(", ")}")
          }
        }

        cursor.downField("data").downField("viewer").downField("zones").withFocus { zones =>
          zones.mapArray(_.map { zoneJson =>
            val withId = zoneJson.mapObject(_.add("zoneId", Json.fromString(zoneId)))
            withId.mapObject(_.add("zoneName", Json.fromString(zoneName)))
          })
        }.top.getOrElse(json)
      }
    })

    futureResponses.map { jsonResponses =>
      Json.obj("data" -> Json.arr(jsonResponses: _*))  // Merge individual results
    }
  }

  private def fetchFirewallEvents(zone: Map[String, String], startTime: String, endTime: String)
                                 (implicit ec: ExecutionContext): Future[Json] = {
    val zoneId = zone("zoneId")
    val zoneName = zone.getOrElse("zoneName", "unknown")
    val query =
      s"""
         |{
         |  viewer {
         |    zones(filter: {zoneTag: "$zoneId"}) {
         |      topIPs: firewallEventsAdaptiveGroups(
         |        limit: 1000,
         |        filter:{
         |          datetime_geq: "$startTime",
         |          datetime_leq: "$endTime"
         |        },
         |        orderBy: [count_DESC]
         |      ) {
         |        count,
         |        avg { sampleInterval }
         |        dimensions {
         |          action
         |          ruleId
         |          source
         |          clientCountryName
         |        }
         |      }
         |    }
         |  }
         |}
         |""".stripMargin

    val request = basicRequest
      .post(uri"${Config.graphqlEndpoint}")
      .header("Authorization", s"Bearer ${Config.apiToken}")
      .header("X-AUTH-EMAIL", Config.authEmail)
      .header("Content-Type", "application/json")
      .body(Map("query" -> query).asJson.noSpaces)
      .response(asString)

    logger.debug(s"Sending GraphQL request to Cloudflare for zone: $zoneId ($zoneName)")

    Future {
      val response = request.send(backend)
      response.body match {
        case Right(jsonStr) =>
          parse(jsonStr) match {
            case Right(json) =>
              logger.debug("Successfully retrieved firewall events for zone: " + zoneId + ": " + json.noSpaces)
              json
            case Left(parseError) =>
              logger.error("Failed to parse Cloudflare GraphQL response", parseError)
              throw parseError
          }
        case Left(error) =>
          logger.error(s"Cloudflare API request failed: $error")
          throw new Exception(error)
      }
    }
  }

  def convertToPrometheusMetrics(json: Json, rulesByZone: Map[String, List[Map[String, String]]]): String = {
    val cursor = json.hcursor
    val zones = cursor.downField("data").as[Json].toOption.toList.flatMap(_.asArray.getOrElse(Vector.empty))
    logger.debug("Raw JSON input to convertToPrometheusMetrics:" + json.noSpaces)
    logger.debug("Extracted top-level zones from JSON: " + zones.size)
    zones.zipWithIndex.foreach { case (zoneJson, i) =>
      logger.debug(s"Zone[$i] JSON: " + zoneJson.noSpaces)
    }

    val metricsBuilder = new StringBuilder

    zones.foreach { zoneJson =>
      val zoneCursor = zoneJson.hcursor
      val nestedZones = zoneCursor.downField("data").downField("viewer").downField("zones").as[List[Json]].getOrElse(Nil)
      logger.debug(s"Nested zones count: " + nestedZones.size)

      nestedZones.foreach { nestedZone =>
        val nestedCursor = nestedZone.hcursor
        val zoneIdOpt = nestedCursor.get[String]("zoneId").toOption
        val zoneNameOpt = nestedCursor.get[String]("zoneName").toOption
        val topIpsList = nestedCursor.downField("topIPs").as[List[Json]].getOrElse(Nil)

        for {
          zoneId <- zoneIdOpt.toList
          zoneName <- zoneNameOpt.toList
          topIp <- topIpsList
          count <- topIp.hcursor.get[Int]("count").toOption
          action <- topIp.hcursor.downField("dimensions").get[String]("action").toOption
          ruleId <- topIp.hcursor.downField("dimensions").get[String]("ruleId").toOption
          source <- topIp.hcursor.downField("dimensions").get[String]("source").toOption
          clientCountryName <- topIp.hcursor.downField("dimensions").get[String]("clientCountryName").toOption
        } {
          logger.debug(s"Looking up rule name for zoneId=$zoneId, ruleId=$ruleId")
          logger.debug(s"Available rules for zone: ${rulesByZone.get(zoneId).getOrElse(Nil).map(_("id")).mkString(", ")}")
          val ruleName = rulesByZone
            .get(zoneId)
            .flatMap(_.find(rule => rule.get("id").contains(ruleId)).flatMap(_.get("description")))
            .getOrElse(ruleId)
          logger.debug(s"Resolved rule name for ruleId=$ruleId: $ruleName")
          logger.debug(s"Preparing to create metrics for zone=$zoneName, action=$action, count=$count")
          val metricLine = s"""cloudflare_top_ip_request_count{zone="$zoneName", action="$action", source="$source", clientCountryName="$clientCountryName", rule="$ruleName"} $count"""
          metricsBuilder.append(metricLine + "\n")
        }
      }
    }

    logger.info(s"Generated metrics with length: ${metricsBuilder.length}")
    logger.debug(s"Final Prometheus metrics:\n${metricsBuilder.toString}")
    metricsBuilder.toString
  }
}