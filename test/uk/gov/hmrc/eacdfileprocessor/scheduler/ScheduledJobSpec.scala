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
import org.scalatest.matchers.should.Matchers.shouldBe
import play.api.Configuration
import uk.gov.hmrc.eacdfileprocessor.helper.TestSupport
import uk.gov.hmrc.eacdfileprocessor.scheduler.SchedulingActor.DeEnrolmentWorkItemPullMessage
import uk.gov.hmrc.eacdfileprocessor.services.LockResponse

import java.time.LocalTime
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.{FiniteDuration, DurationInt}

class ScheduledJobSpec extends TestSupport {

  private val scheduledService = new ScheduledService[Either[Unit, LockResponse]] {
    override def invoke(using ExecutionContext): Future[Either[Unit, LockResponse]] = Future.successful(Left(()))
  }

  private class TestScheduledJob(configMap: Map[String, Any]) extends ScheduledJob {
    override val scheduledMessage = DeEnrolmentWorkItemPullMessage(scheduledService)
    override val config: Configuration = Configuration.from(configMap)
    override val actorSystem: ActorSystem = mock[ActorSystem]
    override val jobName: String = "TestScheduledJob"
    val cancellable: Cancellable = mock[Cancellable]
    var scheduledAt: Option[FiniteDuration] = None
    override def scheduleAtFixedRate(every: FiniteDuration): Cancellable = {
      scheduledAt = Some(every)
      cancellable
    }
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

    "read configured interval" in {
      val job = TestScheduledJob(Map("schedules.TestScheduledJob.interval" -> "5 seconds"))

      job.interval shouldBe Some(5.seconds)
    }

    "read configured UTC start and end times when both are present" in {
      val job = TestScheduledJob(
        Map(
          "schedules.TestScheduledJob.start-time-utc" -> "09:00",
          "schedules.TestScheduledJob.end-time-utc" -> "17:00"
        )
      )

      job.startTimeUtc shouldBe Some(LocalTime.of(9, 0))
      job.endTimeUtc shouldBe Some(LocalTime.of(17, 0))
    }

    "allow runs at any time when no UTC window is configured" in {
      val job = TestScheduledJob(Map.empty)

      job.isWithinAllowedUtcWindow(LocalTime.of(3, 0)) shouldBe true
      job.isWithinAllowedUtcWindow(LocalTime.of(13, 0)) shouldBe true
      job.isWithinAllowedUtcWindow(LocalTime.of(23, 0)) shouldBe true
    }

    "allow runs only between start and end for a same-day UTC window" in {
      val job = TestScheduledJob(
        Map(
          "schedules.TestScheduledJob.start-time-utc" -> "09:00",
          "schedules.TestScheduledJob.end-time-utc" -> "17:00"
        )
      )

      job.isWithinAllowedUtcWindow(LocalTime.of(8, 59)) shouldBe false
      job.isWithinAllowedUtcWindow(LocalTime.of(9, 0)) shouldBe true
      job.isWithinAllowedUtcWindow(LocalTime.of(16, 59)) shouldBe true
      job.isWithinAllowedUtcWindow(LocalTime.of(17, 0)) shouldBe false
    }

    "allow runs correctly for an overnight UTC window" in {
      val job = TestScheduledJob(
        Map(
          "schedules.TestScheduledJob.start-time-utc" -> "22:00",
          "schedules.TestScheduledJob.end-time-utc" -> "05:00"
        )
      )

      job.isWithinAllowedUtcWindow(LocalTime.of(23, 0)) shouldBe true
      job.isWithinAllowedUtcWindow(LocalTime.of(4, 30)) shouldBe true
      job.isWithinAllowedUtcWindow(LocalTime.of(12, 0)) shouldBe false
    }

    "treat partial UTC window configuration as unrestricted" in {
      val job = TestScheduledJob(Map("schedules.TestScheduledJob.start-time-utc" -> "09:00"))

      job.hasPartialUtcWindowConfig shouldBe true
      job.isWithinAllowedUtcWindow(LocalTime.of(2, 0)) shouldBe true
      job.isWithinAllowedUtcWindow(LocalTime.of(18, 0)) shouldBe true
    }

    "create and register schedule when enabled and interval is present" in {
      val job = TestScheduledJob(
        Map(
          "schedules.TestScheduledJob.enabled" -> true,
          "schedules.TestScheduledJob.description" -> "My job",
          "schedules.TestScheduledJob.interval" -> "1 second"
        )
      )

      job.schedule

      job.scheduledAt shouldBe Some(1.seconds)
    }

    "not create or register schedule when enabled but interval is missing" in {
      val job = TestScheduledJob(
        Map(
          "schedules.TestScheduledJob.enabled" -> true
        )
      )

      job.schedule

      job.scheduledAt shouldBe None
    }

    "not create or register schedule when job is disabled" in {
      val job = TestScheduledJob(
        Map(
          "schedules.TestScheduledJob.enabled" -> false,
          "schedules.TestScheduledJob.interval" -> "1 second"
        )
      )

      job.schedule

      job.scheduledAt shouldBe None
    }
  }
}




