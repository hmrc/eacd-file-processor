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
import org.scalatest.matchers.should.Matchers.shouldBe
import play.api.Configuration
import uk.gov.hmrc.eacdfileprocessor.helper.TestSupport
import uk.gov.hmrc.eacdfileprocessor.scheduler.SchedulingActor.DeEnrolmentWorkItemPullMessage
import uk.gov.hmrc.eacdfileprocessor.services.LockResponse

import scala.concurrent.{ExecutionContext, Future}

class ScheduledJobSpec extends TestSupport {

  private val scheduledService = new ScheduledService[Either[Unit, LockResponse]] {
    override def invoke(using ExecutionContext): Future[Either[Unit, LockResponse]] =
      Future.successful(Left(()))
  }

  private class TestScheduledJob(configMap: Map[String, Any]) extends ScheduledJob {
    override val scheduledMessage: DeEnrolmentWorkItemPullMessage =
      DeEnrolmentWorkItemPullMessage(scheduledService)
    override val config: Configuration = Configuration.from(configMap)
    override val actorSystem: ActorSystem = mock[ActorSystem]
    override val jobName: String = "TestScheduledJob"
    override lazy val schedulingActorRef: ActorRef = mock[ActorRef]

    val cancellable: Cancellable = mock[Cancellable]
    var scheduleNextCalled: Boolean = false
    var parsedCron: Option[String] = None

    override private[scheduler] def parseCron(expr: String): Option[CronExpression] = {
      parsedCron = Some(expr)
      super.parseCron(expr)
    }

    override private[scheduler] def scheduleNext(cron: CronExpression): Cancellable = {
      scheduleNextCalled = true
      cancellable
    }
  }

  "ScheduledJob" should {

    "read enabled as false when no enabled configuration is provided" in {
      val job = new TestScheduledJob(Map.empty)
      job.enabled shouldBe false
    }

    "read optional description when configured" in {
      val job = new TestScheduledJob(Map("schedules.TestScheduledJob.description" -> "Runs test schedule"))
      job.description shouldBe Some("Runs test schedule")
    }

    "parse cron with underscores correctly" in {
      val job = new TestScheduledJob(Map("schedules.TestScheduledJob.expression" -> "0_*/15_*_?_*_*"))
      job.expression shouldBe Some("0_*/15_*_?_*_*")
      job.parseCron(job.expression.get).isDefined shouldBe true
    }

    "return None when expression is not configured" in {
      val job = new TestScheduledJob(Map.empty)
      job.expression shouldBe None
    }

    "parse valid cron expression" in {
      val job = new TestScheduledJob(Map.empty)
      job.parseCron("0 */15 * ? * *").isDefined shouldBe true
    }

    "not parse invalid cron expression" in {
      val job = new TestScheduledJob(Map.empty)
      job.parseCron("not-a-cron").isDefined shouldBe false
    }

    "create and register schedule when enabled and expression is valid" in {
      val job = new TestScheduledJob(
        Map(
          "schedules.TestScheduledJob.enabled" -> true,
          "schedules.TestScheduledJob.description" -> "My job",
          "schedules.TestScheduledJob.expression" -> "0_*/15_*_?_*_*"
        )
      )

      job.schedule

      job.scheduleNextCalled shouldBe true
      job.parsedCron shouldBe Some("0_*/15_*_?_*_*")
    }

    "not create or register schedule when enabled but expression is missing" in {
      val job = new TestScheduledJob(Map("schedules.TestScheduledJob.enabled" -> true))

      job.schedule

      job.scheduleNextCalled shouldBe false
      job.parsedCron shouldBe None
    }

    "not create or register schedule when job is disabled" in {
      val job = new TestScheduledJob(
        Map(
          "schedules.TestScheduledJob.enabled" -> false,
          "schedules.TestScheduledJob.expression" -> "0_*/15_*_?_*_*"
        )
      )

      job.schedule

      job.scheduleNextCalled shouldBe false
      job.parsedCron shouldBe None
    }

    "not create schedule when expression is invalid" in {
      val job = new TestScheduledJob(
        Map(
          "schedules.TestScheduledJob.enabled" -> true,
          "schedules.TestScheduledJob.expression" -> "bad expression"
        )
      )

      job.schedule

      job.scheduleNextCalled shouldBe false
      job.parsedCron shouldBe Some("bad expression")
    }
  }
}