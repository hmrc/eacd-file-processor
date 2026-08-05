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
import org.scalatest.matchers.should.Matchers.{shouldBe, should}
import play.api.Configuration
import uk.gov.hmrc.eacdfileprocessor.helper.TestSupport
import uk.gov.hmrc.eacdfileprocessor.scheduler.SchedulingActor.DeEnrolmentWorkItemPullMessage
import uk.gov.hmrc.eacdfileprocessor.services.LockResponse

import java.time.{ZoneOffset, ZonedDateTime}
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}

class ScheduledJobSpec extends TestSupport {

  private val scheduledService = new ScheduledService[Either[Unit, LockResponse]] {
    override def invoke(using ExecutionContext): Future[Either[Unit, LockResponse]] = Future.successful(Left(()))
  }

  private class TestScheduledJob(configMap: Map[String, Any]) extends ScheduledJob {
    override val scheduledMessage: SchedulingActor.ScheduledMessage[?] = DeEnrolmentWorkItemPullMessage(scheduledService)
    override val config: Configuration = Configuration.from(configMap)
    override val actorSystem: ActorSystem = mock[ActorSystem]
    override val jobName: String = "TestScheduledJob"
    override lazy val schedulingActorRef: ActorRef = null

    val cancellable: Cancellable = mock[Cancellable]
    var scheduledWithSpec: Boolean = false

    override private[scheduler] def scheduleNext(spec: uk.gov.hmrc.eacdfileprocessor.config.CronSpec): Cancellable = {
      scheduledWithSpec = true
      cancellable
    }
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

    "read expression when configured" in {
      val job = TestScheduledJob(Map("schedules.TestScheduledJob.expression" -> "0_*/15_*_?_*_*"))
      job.expression shouldBe Some("0 */15 * ? * *")
    }

    "return None when expression is not configured" in {
      val job = TestScheduledJob(Map.empty)
      job.expression shouldBe None
    }

    "parse a valid expression into a cron spec" in {
      val job = TestScheduledJob(Map("schedules.TestScheduledJob.expression" -> "0_0_2_?_*_*"))
      job.cronSpec.isDefined shouldBe true
    }

    "treat an invalid expression as absent cron spec" in {
      val job = TestScheduledJob(Map("schedules.TestScheduledJob.expression" -> "bad-cron"))
      job.cronSpec shouldBe None
    }

    "compute a positive next delay for a valid cron spec" in {
      val job = TestScheduledJob(Map("schedules.TestScheduledJob.expression" -> "0_0_2_?_*_*"))
      val spec = job.cronSpec.value
      val from = ZonedDateTime.of(2026, 8, 5, 1, 0, 0, 0, ZoneOffset.UTC)

      val delay: FiniteDuration = job.nextDelay(spec, from)

      delay.length should be > 0L
    }

    "create and register schedule when enabled and expression is valid" in {
      val job = TestScheduledJob(
        Map(
          "schedules.TestScheduledJob.enabled" -> true,
          "schedules.TestScheduledJob.expression" -> "0_0_2_?_*_*"
        )
      )

      // Guard to prove precondition for scheduling branch.
      job.cronSpec.isDefined shouldBe true

      job.schedule

      job.scheduledWithSpec shouldBe true
    }

    "not create schedule when enabled but expression is missing" in {
      val job = TestScheduledJob(Map("schedules.TestScheduledJob.enabled" -> true))

      job.schedule

      job.scheduledWithSpec shouldBe false
    }

    "not create schedule when enabled but expression is invalid" in {
      val job = TestScheduledJob(
        Map(
          "schedules.TestScheduledJob.enabled" -> true,
          "schedules.TestScheduledJob.expression" -> "bad-cron"
        )
      )

      job.schedule

      job.scheduledWithSpec shouldBe false
    }

    "not create schedule when job is disabled" in {
      val job = TestScheduledJob(
        Map(
          "schedules.TestScheduledJob.enabled" -> false,
          "schedules.TestScheduledJob.expression" -> "0_0_2_?_*_*"
        )
      )

      job.schedule

      job.scheduledWithSpec shouldBe false
    }
  }
}