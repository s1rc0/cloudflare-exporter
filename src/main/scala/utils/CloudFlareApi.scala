package com.monitoring.cloudflare
package utils

import sttp.client3._
import io.circe.Json
import io.circe.parser._
import io.circe.syntax._
import scala.concurrent.{Future, ExecutionContext}
import com.typesafe.scalalogging.LazyLogging

object CloudFlareApi extends LazyLogging {
  Config.validate()

  implicit val backend: SttpBackend[Identity, Any] = HttpURLConnectionBackend()

  def getZones()(implicit ec: ExecutionContext): Future[List[Map[String, String]]] = {
    val baseUri = uri"https://api.cloudflare.com/client/v4/zones"

    def fetchPage(page: Int): Future[List[Map[String, String]]] = {
      val request = basicRequest
        .get(baseUri.addParam("page", page.toString).addParam("per_page", "50"))
        .header("Authorization", s"Bearer ${Config.apiToken}")
        .header("X-Auth-Email", Config.authEmail)
        .header("Content-Type", "application/json")
        .response(asString)

      logger.info(s"Fetching Cloudflare zones, page: $page")

      Future {
        val response = request.send(backend)
        response.body match {
          case Right(jsonStr) =>
            logger.info(s"Cloudflare API Response: $jsonStr")  // LOG THE RESPONSE
            parse(jsonStr) match {
              case Right(json) =>
                val cursor = json.hcursor
                val zones = cursor.downField("result").as[List[Map[String, Json]]].getOrElse(List())

                val activeZones = zones.collect {
                  case zone if zone.get("status").contains(Json.fromString("active")) =>
                    val accountId = zone.get("account").flatMap(_.hcursor.get[String]("id").toOption).getOrElse("")
                    val accountName = zone.get("account").flatMap(_.hcursor.get[String]("name").toOption).getOrElse("")
                    val zoneId = zone.get("id").flatMap(_.asString).getOrElse("")
                    val zoneName = zone.get("name").flatMap(_.asString).getOrElse("")
                    Map(
                      "accountId" -> accountId,
                      "accountName" -> accountName,
                      "zoneId" -> zoneId,
                      "zoneName" -> zoneName
                    )
                }

                logger.info(s"Active Zones Extracted: ${activeZones.mkString(", ")}")

                val totalPages = cursor.downField("result_info").downField("total_pages").as[Int].getOrElse(1)
                if (page < totalPages) {
                  fetchPage(page + 1).map(nextPage => activeZones ++ nextPage)
                } else {
                  Future.successful(activeZones)
                }

              case Left(parseError) =>
                logger.error("Failed to parse Cloudflare API response", parseError)
                throw parseError
            }
          case Left(error) =>
            logger.error(s"Cloudflare API request failed: $error")
            throw new Exception(error)
        }
      }.flatten
    }

    fetchPage(1)
  }

}
