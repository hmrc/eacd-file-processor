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

import org.apache.pekko.actor.{ActorRef, ActorSystem}
import org.apache.pekko.actor.Cancellable
import org.slf4j.{Logger, LoggerFactory}
import play.api.Configuration
import uk.gov.hmrc.eacdfileprocessor.scheduler.SchedulingActor.ScheduledMessage

import java.time.format.DateTimeParseException
import java.time.{Clock, LocalTime}
import scala.concurrent.duration.{DurationInt, FiniteDuration}

trait ScheduledJob {
  private[scheduler] val logger: Logger = LoggerFactory.getLogger(getClass)

  val scheduledMessage: ScheduledMessage[?]
  val config: Configuration
  val actorSystem: ActorSystem
  val jobName: String

  lazy val schedulingActorRef: ActorRef = actorSystem.actorOf(SchedulingActor.props)

  lazy val enabled: Boolean = config.getOptional[Boolean](s"schedules.$jobName.enabled").getOrElse(false)

  lazy val description: Option[String] = config.getOptional[String](s"schedules.$jobName.description")

  lazy val interval: Option[FiniteDuration] = config.getOptional[FiniteDuration](s"schedules.$jobName.interval")

  lazy val startTimeUtc: Option[LocalTime] = readOptionalUtcTime("start-time-utc")

  lazy val endTimeUtc: Option[LocalTime] = readOptionalUtcTime("end-time-utc")

  private[scheduler] lazy val utcWindow: Option[(LocalTime, LocalTime)] =
    (startTimeUtc, endTimeUtc) match {
      case (Some(start), Some(end)) => Some((start, end))
      case _                        => None
    }

  private[scheduler] lazy val hasPartialUtcWindowConfig: Boolean =
    (startTimeUtc.isDefined && endTimeUtc.isEmpty) || (startTimeUtc.isEmpty && endTimeUtc.isDefined)

  private[scheduler] def currentUtcTime: LocalTime = LocalTime.now(Clock.systemUTC())

  private[scheduler] def isWithinAllowedUtcWindow(nowUtc: LocalTime = currentUtcTime): Boolean =
    utcWindow match {
      // No window configured: allow all runs.
      case None =>
        true
      // Equal bounds means full-day window.
      case Some((start, end)) if start == end =>
        true
      // Same-day window (for example 09:00 -> 17:00).
      case Some((start, end)) if start.isBefore(end) =>
        val isAtOrAfterStart = !nowUtc.isBefore(start)
        val isBeforeEnd = nowUtc.isBefore(end)
        isAtOrAfterStart && isBeforeEnd
      // Overnight window (for example 22:00 -> 05:00).
      case Some((start, end)) =>
        val isAtOrAfterStart = !nowUtc.isBefore(start)
        val isBeforeEnd = nowUtc.isBefore(end)
        isAtOrAfterStart || isBeforeEnd
    }

  private[scheduler] lazy val utcWindowSkipReason: Option[String] =
    utcWindow.map((start, end) => s"outside configured UTC run window [$start, $end)")

  private def readOptionalUtcTime(configKey: String): Option[LocalTime] =
    config
      .getOptional[String](s"schedules.$jobName.$configKey")
      .flatMap { value =>
        try {
          Some(LocalTime.parse(value))
        } catch {
          case _: DateTimeParseException =>
            logger.warn(s"Ignoring invalid UTC time for schedules.$jobName.$configKey. Expected format like HH:mm, got '$value'")
            None
        }
      }

  private[scheduler] def scheduleAtFixedRate(every: FiniteDuration): Cancellable =
    actorSystem.scheduler.scheduleAtFixedRate(0.seconds, every, schedulingActorRef, scheduledMessage)(actorSystem.dispatcher)

  lazy val schedule: Unit = {

    (enabled, interval) match {
      case (true, Some(scheduleInterval)) =>
        if (hasPartialUtcWindowConfig) {
          logger.warn(s"Ignoring UTC run window for $jobName because both start-time-utc and end-time-utc must be configured together")
        }
        scheduleAtFixedRate(scheduleInterval)
        logger.info(s"Scheduler for $jobName has been started")
      case (true, None) =>
        logger.info(s"Scheduler for $jobName is disabled as there is no interval configured")
      case (false, _) =>
        logger.info(s"Scheduler for $jobName is disabled by configuration")
    }

  }

}