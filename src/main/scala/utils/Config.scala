package com.monitoring.cloudflare
package utils

import com.typesafe.scalalogging.LazyLogging

import scala.sys.env

object Config extends LazyLogging {
  val apiToken: String = env.getOrElse("CLOUDFLARE_API_TOKEN", "")
  val authEmail: String = env.getOrElse("CLOUDFLARE_AUTH_EMAIL", "")
  val customZoneIds: Option[String] = sys.env.get("CLOUDFLARE_ZONES_IDS")
  val accountIds: Set[String] = env.get("CLOUDFLARE_ACCOUNT_ID")
    .map(_.split(",").map(_.trim).toSet)
    .getOrElse(Set.empty)
  val graphqlEndpoint: String = "https://api.cloudflare.com/client/v4/graphql"
  val disableFreePlanZones: Boolean = env.get("CLOUDFLARE_DISABLE_FREE_PLAN_ZONES").forall(_.toBoolean)

  def validate(): Unit = {
    if (apiToken.isEmpty || authEmail.isEmpty) {
      logger.error("CLOUDFLARE_API_TOKEN and CLOUDFLARE_AUTH_EMAIL must be set in environment variables")
      throw new IllegalStateException("Missing Cloudflare API credentials")
    }
  }
}
