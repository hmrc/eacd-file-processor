/*
 * Copyright 2026 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.gov.hmrc.eacdfileprocessor.scheduler

import org.apache.pekko.actor.{ActorRef, ActorSystem, Cancellable}
import org.slf4j.{Logger, LoggerFactory}
import play.api.Configuration
import uk.gov.hmrc.eacdfileprocessor.scheduler.SchedulingActor.ScheduledMessage

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration

trait ScheduledJob {
  private[scheduler] val logger: Logger = LoggerFactory.getLogger(getClass)

  val scheduledMessage: ScheduledMessage[?]
  val config: Configuration
  val actorSystem: ActorSystem
  val jobName: String

  implicit lazy val ec: ExecutionContext = actorSystem.dispatcher

  lazy val schedulingActorRef: ActorRef = actorSystem.actorOf(SchedulingActor.props)

  lazy val enabled: Boolean = config.getOptional[Boolean](s"schedules.$jobName.enabled").getOrElse(false)

  lazy val description: Option[String] = config.getOptional[String](s"schedules.$jobName.description")

  lazy val interval: Option[FiniteDuration] = config.getOptional[FiniteDuration](s"schedules.$jobName.interval")

  lazy val schedule: Unit = {

    (enabled, interval) match {
      case (true, Some(duration)) =>
        scheduleAtFixedRate(duration)
        logger.info(s"Scheduler for $jobName has been started with interval: $duration")
      case (true, None) =>
        logger.info(s"Scheduler for $jobName is disabled as there is no interval configured")
      case (false, _) =>
        logger.info(s"Scheduler for $jobName is disabled by configuration")
    }

  }

  private def scheduleAtFixedRate(duration: FiniteDuration): Cancellable = {
    actorSystem.scheduler.scheduleAtFixedRate(
      initialDelay = duration,
      interval = duration,
      receiver = schedulingActorRef,
      message = scheduledMessage
    )
  }

}
