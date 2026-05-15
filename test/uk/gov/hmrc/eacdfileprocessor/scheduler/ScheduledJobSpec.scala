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

import com.typesafe.config.{Config, ConfigFactory}
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.testkit.TestKit
import org.mockito.Mockito.*
import org.scalatest.BeforeAndAfterAll
import org.scalatestplus.play.PlaySpec
import org.slf4j
import org.slf4j.Logger
import play.api.{Configuration, LoggerLike}
import uk.gov.hmrc.eacdfileprocessor.helper.AssertionHelpers
import uk.gov.hmrc.eacdfileprocessor.scheduler.SchedulingActor.ProcessApprovedFileMessage
import uk.gov.hmrc.eacdfileprocessor.services.ProcessApprovedFileService


class ScheduledJobSpec extends PlaySpec
  with BeforeAndAfterAll
  with AssertionHelpers {

  val system: ActorSystem = ActorSystem()
  val mockProcessApprovedFileService: ProcessApprovedFileService = mock(classOf[ProcessApprovedFileService])

  override def afterAll(): Unit = {
    TestKit.shutdownActorSystem(system)
    super.afterAll()
  }

  def loggerLike(inspectedLogger: Logger): LoggerLike = {
    new LoggerLike {
      override val logger: slf4j.Logger = inspectedLogger
    }
  }

  "A Scheduled Job" should {

    "log that a job has started when enabled" in {

      val enabledJob: ScheduledJob = new ScheduledJob {
        override val config: Configuration = mock(classOf[Configuration])
        override val actorSystem: ActorSystem = system
        override val jobName = "TestJob"
        override val scheduledMessage: ProcessApprovedFileMessage = ProcessApprovedFileMessage(mockProcessApprovedFileService)
      }

      when(enabledJob.config.getOptional[Boolean](s"schedules.${enabledJob.jobName}.enabled")).thenReturn(Some(true))
      when(enabledJob.config.getOptional[String](s"schedules.${enabledJob.jobName}.description")).thenReturn(Some("Test Job's description"))
      when(enabledJob.config.getOptional[String](s"schedules.${enabledJob.jobName}.expression")).thenReturn(Some("0/10 0 0 ? * * *"))

      withCaptureOfLoggingFrom(loggerLike(enabledJob.logger)) { events =>
        enabledJob.schedule

        events.exists(event => event.getMessage.contains(s"Scheduler for ${enabledJob.jobName}")) mustBe true
      }
    }

    "log that a job has been explicitly disabled" in {

      val disabledJob: ScheduledJob = new ScheduledJob {
        override val config: Configuration = mock(classOf[Configuration])
        override val actorSystem: ActorSystem = system
        override val jobName = "TestJob"
        override val scheduledMessage: ProcessApprovedFileMessage = ProcessApprovedFileMessage(mockProcessApprovedFileService)
      }

      when(disabledJob.config.getOptional[Boolean](s"schedules.${disabledJob.jobName}.enabled")).thenReturn(Some(false))
      when(disabledJob.config.getOptional[String](s"schedules.${disabledJob.jobName}.description")).thenReturn(Some("Test Job's description"))
      when(disabledJob.config.getOptional[String](s"schedules.${disabledJob.jobName}.expression")).thenReturn(Some("0/10 0 0 ? * * *"))

      withCaptureOfLoggingFrom(loggerLike(disabledJob.logger)) { events =>
        disabledJob.schedule

        events.exists(event => event.getMessage.contains(s"${disabledJob.jobName} is disabled by configuration")) mustBe true
      }
    }

    "log that a job has been disabled due to no quartz expression" in {

      val disabledJob: ScheduledJob = new ScheduledJob {
        override val config: Configuration = mock(classOf[Configuration])
        override val actorSystem: ActorSystem = system
        override val jobName = "TestJob"
        override val scheduledMessage: ProcessApprovedFileMessage = ProcessApprovedFileMessage(mockProcessApprovedFileService)
      }

      when(disabledJob.config.getOptional[Boolean](s"schedules.${disabledJob.jobName}.enabled")).thenReturn(Some(true))
      when(disabledJob.config.getOptional[String](s"schedules.${disabledJob.jobName}.description")).thenReturn(Some("Test Job's description"))
      when(disabledJob.config.getOptional[String](s"schedules.${disabledJob.jobName}.expression")).thenReturn(None)

      withCaptureOfLoggingFrom(loggerLike(disabledJob.logger)) { events =>
        disabledJob.schedule

        events.exists(event => event.getMessage.contains(s"${disabledJob.jobName} is disabled as there is no quartz expression")) mustBe true
      }
    }

  }
}

object SchedulingFunctionalTest {
  lazy val sampleConfiguration: Config = {
    ConfigFactory.parseString(
      """
    akka {
      event-handlers = ["akka.testkit.TestEventListener"]
      loglevel = "INFO"
      quartz {
        defaultTimezone = "UTC"
      }
    }

    schedules {
      TestJob {
        description = "A cron job that fires off every 30 seconds"
        expression = "*/30 * * ? * *"
      }
    }

    """.stripMargin)
  }
}