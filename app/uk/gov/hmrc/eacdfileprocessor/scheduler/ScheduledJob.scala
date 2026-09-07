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
import org.quartz.CronExpression
import org.slf4j.{Logger, LoggerFactory}
import play.api.Configuration
import uk.gov.hmrc.eacdfileprocessor.scheduler.SchedulingActor.ScheduledMessage

import java.util.Date
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.DurationLong
import scala.util.Try

trait ScheduledJob {
  private[scheduler] val logger: Logger = LoggerFactory.getLogger(getClass)

  val scheduledMessage: ScheduledMessage[?]
  val config: Configuration
  val actorSystem: ActorSystem
  val jobName: String

  implicit lazy val ec: ExecutionContext = actorSystem.dispatcher

  lazy val schedulingActorRef: ActorRef = actorSystem.actorOf(SchedulingActor.props)

  lazy val enabled: Boolean =
    config.getOptional[Boolean](s"schedules.$jobName.enabled").getOrElse(false)

  lazy val description: Option[String] =
    config.getOptional[String](s"schedules.$jobName.description")
  
  lazy val expression: Option[String] =
    config.getOptional[String](s"schedules.$jobName.expression")

  private[scheduler] def parseCron(expr: String): Option[CronExpression] =
    Try(new CronExpression(expr)).toOption

  private[scheduler] def scheduleNext(cron: CronExpression): Cancellable = {
    val now = new Date()
    val next = cron.getNextValidTimeAfter(now)

    if (next == null) {
      logger.warn(s"No next valid fire time for $jobName; schedule will not run again")
      actorSystem.scheduler.scheduleOnce(1.day)(())
    } else {
      val delayMs: Long = math.max(0L, next.getTime - now.getTime)
      actorSystem.scheduler.scheduleOnce(delayMs.millis) {
        schedulingActorRef ! scheduledMessage
        scheduleNext(cron)
        ()
      }
    }
  }

  lazy val schedule: Unit =
    (enabled, expression) match {
      case (true, Some(expr)) =>
        parseCron(expr) match {
          case Some(cron) =>
            scheduleNext(cron)
            logger.info(s"Scheduler for $jobName has been started with expression: $expr")
          case None =>
            logger.warn(s"Scheduler for $jobName is enabled but expression is invalid: $expr")
        }
      case (true, None) =>
        logger.info(s"Scheduler for $jobName is enabled but no expression is configured")
      case (false, _) =>
        logger.info(s"Scheduler for $jobName is disabled by configuration")
    }
}
