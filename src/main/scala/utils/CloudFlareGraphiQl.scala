package com.monitoring.cloudflare
package utils

import sttp.client3._
import io.circe.Json
import io.circe.parser._
import io.circe.syntax._
import scala.concurrent.{Future, ExecutionContext}
import com.typesafe.scalalogging.LazyLogging

object CloudFlareGraphiQl extends LazyLogging {

  Config.validate()

  implicit val backend: SttpBackend[Identity, Any] = HttpURLConnectionBackend()

  def fetchFirewallEventsForZones(zones: List[Map[String, String]], startTime: String, endTime: String)
                                 (implicit ec: ExecutionContext): Future[Json] = {
    val validZones = zones.filterNot(z => Config.disabledZonesFirewall.contains(z("zoneId")))
    if (validZones.isEmpty) {
      logger.warn("No valid zones with firewall access found. Skipping request.")
      return Future.successful(Json.obj("data" -> Json.arr()))
    }

    val futureResponses = Future.sequence(validZones.map { zone =>
      val zoneId = zone("zoneId")
      val zoneName = zone("zoneName")
      fetchFirewallEvents(zoneId, startTime, endTime).map { json =>
        json.hcursor.downField("data").downField("viewer").downField("zones").withFocus { zones =>
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

  private def fetchFirewallEvents(zoneId: String, startTime: String, endTime: String)
                                 (implicit ec: ExecutionContext): Future[Json] = {
    val query =
      s"""
         |{
         |  viewer {
         |    zones(filter: {zoneTag: "$zoneId"}) {
         |      topIPs: firewallEventsAdaptiveGroups(
         |        limit: 1,
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

    logger.info(s"Sending GraphQL request to Cloudflare for zone: $zoneId")

    Future {
      val response = request.send(backend)
      response.body match {
        case Right(jsonStr) =>
          parse(jsonStr) match {
            case Right(json) =>
              logger.info("Successfully retrieved firewall events for zone: " + zoneId + ": " + json.noSpaces)
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

  def convertToPrometheusMetrics(json: Json): String = {
    val cursor = json.hcursor
    val zones = cursor.downField("data").as[Json].toOption.toList.flatMap(_.asArray.getOrElse(Vector.empty))
    logger.debug("Raw JSON input to convertToPrometheusMetrics:\n" + json.spaces2)
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
      } {
        logger.debug(s"Preparing to create metrics for zone=$zoneName, action=$action, count=$count")
        val metricLine = s"""cloudflare_top_ip_request_count{zone="$zoneName", action="$action", source="$source", rule="$ruleId"} $count"""
        metricsBuilder.append(metricLine + "\n")
      }
    }
    }

    logger.info(s"Generated metrics with length: ${metricsBuilder.length}")
    logger.debug(s"Final Prometheus metrics:\n${metricsBuilder.toString}")
    metricsBuilder.toString
  }
}