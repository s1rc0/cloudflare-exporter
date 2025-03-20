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

  def fetchFirewallEventsForZones(zoneIds: List[String], startTime: String, endTime: String)
                                 (implicit ec: ExecutionContext): Future[Json] = {
    val validZones = zoneIds.filterNot(Config.disabledZonesFirewall.contains)
    if (validZones.isEmpty) {
      logger.warn("No valid zones with firewall access found. Skipping request.")
      return Future.successful(Json.obj("data" -> Json.arr()))
    }

    val batchSize = 10  // Adjust this if necessary based on Cloudflare's limits
    val zoneBatches = validZones.grouped(batchSize).toList

    val futureResponses = Future.sequence(zoneBatches.map { batch =>
      val zoneFilter = batch.map(id => s""""$id"""").mkString(",")

      val query =
        s"""
           |{
           |  viewer {
           |    zones(filter: {zoneTag_in: [$zoneFilter]}) {
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

      logger.info(s"Sending batch GraphQL request for ${batch.size} zones (filtered from ${zoneIds.size})")

      Future {
        val response = request.send(backend)
        response.body match {
          case Right(jsonStr) =>
            parse(jsonStr) match {
              case Right(json) =>
                logger.info(s"Successfully retrieved firewall events for batch: ${batch.mkString(", ")}")
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
    })

    futureResponses.map { jsonResponses =>
      Json.obj("data" -> Json.arr(jsonResponses: _*))  // Merge batch results
    }
  }

  def fetchFirewallEvents(zoneId: String, startTime: String, endTime: String)
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
              logger.info("Successfully retrieved firewall events")
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
}