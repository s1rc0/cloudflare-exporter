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

            val filteredZones = zones.flatMap { zone =>
                  val isActive = zone.get("status").contains(Json.fromString("active"))
                  val accountIdOpt = zone.get("account").flatMap(_.hcursor.downField("id").as[String].toOption)
                  val planName = zone.get("plan").flatMap(_.hcursor.get[String]("name").toOption).getOrElse("")

                  val isAllowedPlan = !Config.disableFreePlanZones || planName != "Free Website"

                  if (isActive && isAllowedPlan && (Config.accountIds.isEmpty || accountIdOpt.exists(Config.accountIds.contains))) {
                    val accountName = zone.get("account").flatMap(_.hcursor.get[String]("name").toOption).getOrElse("")
                    val zoneId = zone.get("id").flatMap(_.asString).getOrElse("")
                    val zoneName = zone.get("name").flatMap(_.asString).getOrElse("")
                    Some(Map(
                      "accountId" -> accountIdOpt.getOrElse(""),
                      "accountName" -> accountName,
                      "zoneId" -> zoneId,
                      "zoneName" -> zoneName
                    ))
                  } else None
                }

                logger.info(s"Filtered Zones Extracted: ${filteredZones.mkString(", ")}")

                val totalPages = cursor.downField("result_info").downField("total_pages").as[Int].getOrElse(1)
                if (page < totalPages) {
                  fetchPage(page + 1).map(nextPage => filteredZones ++ nextPage)
                } else {
                  Future.successful(filteredZones)
                }

              case Left(parseError) =>
                logger.error("Failed to parse Cloudflare API response", parseError)
                throw parseError
            }
              case Left(error) =>
                logger.error(s"Cloudflare API request failed: $error")
                System.err.println("❌ Cloudflare API token is invalid. Exiting.")
                System.exit(1)
                throw new Exception(error) // This line is unreachable but kept for clarity
        }
      }.flatten
    }

    fetchPage(1)
  }

  def getIpAccessRules(zoneId: String)(implicit ec: ExecutionContext): Future[List[Map[String, Json]]] = {
    def fetchPage(page: Int): Future[List[Map[String, Json]]] = {
      val request = basicRequest
        .get(uri"https://api.cloudflare.com/client/v4/zones/$zoneId/firewall/access_rules/rules?page=$page&per_page=50")
        .header("Authorization", s"Bearer ${Config.apiToken}")
        .header("X-Auth-Email", Config.authEmail)
        .header("Content-Type", "application/json")
        .response(asString)

      Future {
        val response = request.send(backend)
        response.body match {
          case Right(jsonStr) =>
            parse(jsonStr) match {
              case Right(json) =>
                logger.debug(s"Successfully retrieved IP access rules for zone $zoneId (page $page): ${json.noSpaces}")
                val cursor = json.hcursor
                val rules = cursor.downField("result").as[List[Json]].getOrElse(List.empty)
                  .map(_.asObject.get.toMap)
                val totalPages = cursor.downField("result_info").downField("total_pages").as[Int].getOrElse(1)
                if (page < totalPages) {
                  fetchPage(page + 1).map(nextPage => rules ++ nextPage)
                } else {
                  Future.successful(rules)
                }
              case Left(parseError) =>
                logger.error(s"Failed to parse IP access rules response for zone $zoneId", parseError)
                throw parseError
            }
          case Left(error) =>
            logger.error(s"Failed to fetch IP access rules for account $zoneId: $error")
            throw new Exception(error)
        }
      }.flatten
    }

    fetchPage(1)
  }

  def getRules(zoneId: String)(implicit ec: ExecutionContext): Future[List[Map[String, Json]]] = {
    def fetchPage(page: Int): Future[List[Map[String, Json]]] = {
      val request = basicRequest
        .get(uri"https://api.cloudflare.com/client/v4/zones/$zoneId/firewall/rules?page=$page&per_page=50")
        .header("Authorization", s"Bearer ${Config.apiToken}")
        .header("Content-Type", "application/json")
        .response(asString)

      Future {
        val response = request.send(backend)
        response.body match {
          case Right(jsonStr) =>
            parse(jsonStr) match {
              case Right(json) =>
                logger.debug("Successfully retrieved rules for zone: " + zoneId + ": " + json.noSpaces)
                val cursor = json.hcursor
                val rules = cursor.downField("result").as[List[Json]].getOrElse(List.empty).map(_.asObject.get.toMap)

                val totalPages = cursor.downField("result_info").downField("total_pages").as[Int].getOrElse(1)
                if (page < totalPages) {
                  fetchPage(page + 1).map(nextPage => rules ++ nextPage)
                } else {
                  Future.successful(rules)
                }

              case Left(parseError) =>
                logger.error("Failed to parse rules response", parseError)
                throw parseError
            }

          case Left(error) =>
            logger.error(s"Failed to fetch rules for zone $zoneId: $error")
            throw new Exception(error)
        }
      }.flatten
    }

    fetchPage(1)
  }
  def getRateLimitRules(zoneId: String)(implicit ec: ExecutionContext): Future[List[Map[String, String]]] = {
    def fetchPage(page: Int): Future[List[Map[String, String]]] = {
      val request = basicRequest
        .get(uri"https://api.cloudflare.com/client/v4/zones/$zoneId/rate_limits?page=$page&per_page=50")
        .header("Authorization", s"Bearer ${Config.apiToken}")
        .header("Content-Type", "application/json")
        .response(asString)

      Future {
        val response = request.send(backend)
        response.body match {
          case Right(jsonStr) =>
            parse(jsonStr) match {
              case Right(json) =>
                logger.debug(s"Successfully retrieved rate limit rules for zone $zoneId: ${json.noSpaces}")
                val cursor = json.hcursor
                val rules = cursor.downField("result").as[List[Json]].getOrElse(List.empty).map { rule =>
                  Map(
                    "id" -> rule.hcursor.get[String]("id").getOrElse(""),
                    "description" -> rule.hcursor.get[String]("description").getOrElse("")
                  )
                }

                val totalPages = cursor.downField("result_info").downField("total_pages").as[Int].getOrElse(1)
                if (page < totalPages) {
                  fetchPage(page + 1).map(nextPage => rules ++ nextPage)
                } else {
                  Future.successful(rules)
                }

              case Left(parseError) =>
                logger.error(s"Failed to parse rate limit rules response for zone $zoneId", parseError)
                throw parseError
            }

          case Left(error) =>
            logger.error(s"Failed to fetch rate limit rules for zone $zoneId: $error")
            throw new Exception(error)
        }
      }.flatten
    }

    fetchPage(1)
  }
  def getUserAgentRules(zoneId: String)(implicit ec: ExecutionContext): Future[List[Map[String, Json]]] = {
    def fetchPage(page: Int): Future[List[Map[String, Json]]] = {
      val request = basicRequest
        .get(uri"https://api.cloudflare.com/client/v4/zones/$zoneId/firewall/ua_rules?page=$page&per_page=50")
        .header("Authorization", s"Bearer ${Config.apiToken}")
        .header("X-Auth-Email", Config.authEmail)
        .header("Content-Type", "application/json")
        .response(asString)

      Future {
        val response = request.send(backend)
        response.body match {
          case Right(jsonStr) =>
            parse(jsonStr) match {
              case Right(json) =>
                logger.debug(s"Successfully retrieved User-Agent rules for zone $zoneId (page $page): ${json.noSpaces}")
                val cursor = json.hcursor
                val rules = cursor.downField("result").as[List[Json]].getOrElse(List.empty)
                  .map(_.asObject.get.toMap)
                val totalPages = cursor.downField("result_info").downField("total_pages").as[Int].getOrElse(1)
                if (page < totalPages) {
                  fetchPage(page + 1).map(nextPage => rules ++ nextPage)
                } else {
                  Future.successful(rules)
                }
              case Left(parseError) =>
                logger.error(s"Failed to parse User-Agent rules response for zone $zoneId", parseError)
                throw parseError
            }
          case Left(error) =>
            logger.error(s"Failed to fetch User-Agent rules for zone $zoneId: $error")
            throw new Exception(error)
        }
      }.flatten
    }

    fetchPage(1)
  }

  def getRulesets(zoneId: String)(implicit ec: ExecutionContext): Future[List[Map[String, Json]]] = {
    def fetchPage(page: Int): Future[List[Map[String, Json]]] = {
      val baseUri = uri"https://api.cloudflare.com/client/v4/zones/$zoneId/rulesets?page=$page&per_page=50"

      val request = basicRequest
        .get(baseUri)
        .header("Authorization", s"Bearer ${Config.apiToken}")
        .header("Content-Type", "application/json")
        .response(asString)

      Future {
        val response = request.send(backend)
        response.body match {
          case Right(jsonStr) =>
            parse(jsonStr) match {
              case Right(json) =>
                logger.debug(s"Successfully retrieved rulesets for zone $zoneId (page $page): ${json.noSpaces}")
                val cursor = json.hcursor
                val rules = cursor.downField("result").as[List[Json]].getOrElse(List.empty).map(_.asObject.get.toMap)
                val totalPages = cursor.downField("result_info").downField("total_pages").as[Int].getOrElse(1)
                if (page < totalPages) {
                  fetchPage(page + 1).map(next => rules ++ next)
                } else {
                  Future.successful(rules)
                }
              case Left(err) =>
                logger.error(s"Failed to parse rulesets response for zone $zoneId", err)
                throw err
            }
          case Left(err) =>
            logger.error(s"Failed to fetch rulesets for zone $zoneId: $err")
            throw new Exception(err)
        }
      }.flatten
    }

    fetchPage(1)
  }
}

