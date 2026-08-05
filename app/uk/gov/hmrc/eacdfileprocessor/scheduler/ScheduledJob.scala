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
import uk.gov.hmrc.eacdfileprocessor.config.{CronExpressionParser, CronSpec}
import uk.gov.hmrc.eacdfileprocessor.scheduler.SchedulingActor.ScheduledMessage

import java.time.{ZoneOffset, ZonedDateTime, Duration as JavaDuration}
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.{DurationLong, FiniteDuration}
import scala.util.Try
import scala.util.control.NonFatal

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

  private[scheduler] lazy val expression: Option[String] =
    config.getOptional[String](s"schedules.$jobName.expression")
      .map(_.replace('_', ' ').trim)
      .filter(_.nonEmpty)

  private[scheduler] lazy val cronSpec: Option[CronSpec] =
    expression.flatMap { expr =>
      Try(CronExpressionParser.parse(expr)).toOption.orElse {
        logger.warn(s"Invalid cron expression for schedules.$jobName.expression: '$expr'")
        None
      }
    }

  private[scheduler] def nowUtc: ZonedDateTime =
    ZonedDateTime.now(ZoneOffset.UTC)

  private[scheduler] def nextDelay(spec: CronSpec, from: ZonedDateTime): FiniteDuration = {
    val nextRun = spec.nextAfter(from)
    val millis = JavaDuration.between(from, nextRun).toMillis.max(0L)
    millis.millis
  }

  private[scheduler] def triggerAndReschedule(spec: CronSpec): Unit = {
    try {
      logger.debug(s"Triggering scheduled job: $jobName")
      schedulingActorRef ! scheduledMessage
    } catch {
      case NonFatal(e) =>
        logger.error(s"Scheduled job $jobName failed while dispatching message", e)
    } finally {
      scheduleNext(spec)
    }
  }

  private[scheduler] def scheduleNext(spec: CronSpec): Cancellable = {
    val from = nowUtc

    try {
      val delay = nextDelay(spec, from)
      logger.debug(s"Next run for $jobName scheduled in $delay from $from")
      actorSystem.scheduler.scheduleOnce(delay) {
        triggerAndReschedule(spec)
      }
    } catch {
      case NonFatal(e) =>
        logger.error(s"Failed to schedule next run for $jobName", e)
        throw e
    }
  }

  lazy val schedule: Unit = {
    (enabled, cronSpec) match {
      case (true, Some(spec)) =>
        scheduleNext(spec)
        logger.info(s"Scheduler for $jobName started with expression: ${expression.getOrElse("")}")
      case (true, None) =>
        logger.info(s"Scheduler for $jobName is disabled as there is no valid expression configured")
      case (false, _) =>
        logger.info(s"Scheduler for $jobName is disabled by configuration")
    }
  }
}