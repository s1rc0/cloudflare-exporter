package com.monitoring.cloudflare
package actors

import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import com.typesafe.scalalogging.LazyLogging
import scala.concurrent.ExecutionContext
import utils.CloudFlareGraphiQl
import io.circe.Json

object GraphQlActor extends LazyLogging {

  sealed trait Command
  private final case class FetchFirewallMetrics(
                                         zones: List[Map[String, String]],
                                         startTime: String,
                                         endTime: String,
                                         dispatcher: ActorRef[com.monitoring.cloudflare.actors.DispatcherActor.Command],
                                         replyTo: ActorRef[Json]
                                       ) extends Command

  def apply(): Behavior[Command] = Behaviors.setup { context =>
    implicit val ec: ExecutionContext = context.executionContext

    Behaviors.receiveMessage {
      case FetchFirewallMetrics(zones, startTime, endTime, dispatcher, replyTo) =>
        context.log.info(s"📡 Fetching firewall metrics for ${zones.size} zones")
        CloudFlareGraphiQl.fetchFirewallEventsForZones(zones, startTime, endTime, dispatcher)
        Behaviors.same
    }
  }
}