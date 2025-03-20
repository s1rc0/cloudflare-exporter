package com.monitoring.cloudflare
package utils

import sys.env
import com.typesafe.scalalogging.LazyLogging
import sttp.client3._
import io.circe.Json
import io.circe.parser._
import io.circe.syntax._
import scala.util.{Try, Success, Failure}

object CloudFlareConfig extends LazyLogging {
  val apiToken: String = env.getOrElse("CLOUDFLARE_API_TOKEN", "")
  val authEmail: String = env.getOrElse("CLOUDFLARE_AUTH_EMAIL", "")
  val accountId: String = env.getOrElse("CLOUDFLARE_ACCOUNT_ID", "")
  val graphqlEndpoint: String = "https://api.cloudflare.com/client/v4/graphql"

  def validate(): Unit = {
    if (apiToken.isEmpty || authEmail.isEmpty) {
      logger.error("CLOUDFLARE_API_TOKEN and CLOUDFLARE_AUTH_EMAIL must be set in environment variables")
      throw new IllegalStateException("Missing Cloudflare API credentials")
    }
  }
}