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
import org.apache.pekko.extension.quartz.QuartzSchedulerExtension
import org.mockito.ArgumentMatchers.{any, eq as eqTo}
import org.mockito.Mockito.{never, verify}
import org.scalatest.matchers.should.Matchers.shouldBe
import play.api.Configuration
import uk.gov.hmrc.eacdfileprocessor.helper.TestSupport
import uk.gov.hmrc.eacdfileprocessor.scheduler.SchedulingActor.FileWorkItemPullMessage
import uk.gov.hmrc.eacdfileprocessor.services.LockResponse

import scala.concurrent.{ExecutionContext, Future}

class ScheduledJobSpec extends TestSupport {

  private val scheduledService = new ScheduledService[Either[Unit, LockResponse]] {
    override def invoke(using ExecutionContext): Future[Either[Unit, LockResponse]] = Future.successful(Left(()))
  }

  private class TestScheduledJob(configMap: Map[String, Any]) extends ScheduledJob {
    override val scheduledMessage = FileWorkItemPullMessage(scheduledService)
    override val config: Configuration = Configuration.from(configMap)
    override val actorSystem: ActorSystem = mock[ActorSystem]
    override val jobName: String = "TestScheduledJob"
    override lazy val scheduler: QuartzSchedulerExtension = mock[QuartzSchedulerExtension]
    override lazy val schedulingActorRef: ActorRef = null
  }

  "ScheduledJob" should {

    "read enabled as false when no enabled configuration is provided" in {
      val job = TestScheduledJob(Map.empty)

      job.enabled shouldBe false
    }

    "read optional description when configured" in {
      val job = TestScheduledJob(Map("schedules.TestScheduledJob.description" -> "Runs test schedule"))

      job.description shouldBe Some("Runs test schedule")
    }

    "replace underscores with spaces in expression" in {
      val job = TestScheduledJob(Map("schedules.TestScheduledJob.expression" -> "0/5_*_*_?_*_*_*"))

      job.expression shouldBe "0/5 * * ? * * *"
    }

    "create and register schedule when enabled and expression is present" in {
      val job = TestScheduledJob(
        Map(
          "schedules.TestScheduledJob.enabled" -> true,
          "schedules.TestScheduledJob.description" -> "My job",
          "schedules.TestScheduledJob.expression" -> "0/1_*_*_?_*_*_*"
        )
      )

      job.schedule

      verify(job.scheduler).createSchedule("TestScheduledJob", Some("My job"), "0/1 * * ? * * *")
      verify(job.scheduler).schedule(eqTo("TestScheduledJob"), any[ActorRef], any())
    }

    "not create or register schedule when enabled but expression is missing" in {
      val job = TestScheduledJob(
        Map(
          "schedules.TestScheduledJob.enabled" -> true
        )
      )

      job.schedule

      verify(job.scheduler, never()).createSchedule("TestScheduledJob", job.description, job.expression)
      verify(job.scheduler, never()).schedule(eqTo("TestScheduledJob"), any[ActorRef], any())
    }

    "not create or register schedule when job is disabled" in {
      val job = TestScheduledJob(
        Map(
          "schedules.TestScheduledJob.enabled" -> false,
          "schedules.TestScheduledJob.expression" -> "0/1_*_*_?_*_*_*"
        )
      )

      job.schedule

      verify(job.scheduler, never()).createSchedule("TestScheduledJob", job.description, "0/1 * * ? * * *")
      verify(job.scheduler, never()).schedule(eqTo("TestScheduledJob"), any[ActorRef], any())
    }
  }
}




