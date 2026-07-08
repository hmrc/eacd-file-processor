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
import org.scalatest.matchers.should.Matchers.shouldBe
import play.api.Configuration
import uk.gov.hmrc.eacdfileprocessor.helper.TestSupport
import uk.gov.hmrc.eacdfileprocessor.scheduler.SchedulingActor.DeEnrolmentWorkItemPullMessage
import uk.gov.hmrc.eacdfileprocessor.services.LockResponse

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.*

class ScheduledJobSpec extends TestSupport {

  private val scheduledService = new ScheduledService[Either[Unit, LockResponse]] {
    override def invoke(using ExecutionContext): Future[Either[Unit, LockResponse]] = Future.successful(Left(()))
  }

  private class TestScheduledJob(configMap: Map[String, Any], testActorSystem: ActorSystem) extends ScheduledJob {
    override val scheduledMessage = DeEnrolmentWorkItemPullMessage(scheduledService)
    override val config: Configuration = Configuration.from(configMap)
    override val actorSystem: ActorSystem = testActorSystem
    override val jobName: String = "TestScheduledJob"
    override lazy val schedulingActorRef: ActorRef = null
  }

  "ScheduledJob" should {

    "read enabled as false when no enabled configuration is provided" in {
      val testActorSystem = ActorSystem("test")
      val job = TestScheduledJob(Map.empty, testActorSystem)

      job.enabled shouldBe false

      testActorSystem.terminate()
    }

    "read optional description when configured" in {
      val testActorSystem = ActorSystem("test")
      val job = TestScheduledJob(Map("schedules.TestScheduledJob.description" -> "Runs test schedule"), testActorSystem)

      job.description shouldBe Some("Runs test schedule")

      testActorSystem.terminate()
    }

    "parse interval duration when configured" in {
      val testActorSystem = ActorSystem("test")
      val job = TestScheduledJob(Map("schedules.TestScheduledJob.interval" -> "1 second"), testActorSystem)

      job.interval shouldBe Some(1.second)

      testActorSystem.terminate()
    }

    "parse millisecond interval when configured" in {
      val testActorSystem = ActorSystem("test")
      val job = TestScheduledJob(Map("schedules.TestScheduledJob.interval" -> "100 milliseconds"), testActorSystem)

      job.interval shouldBe Some(100.milliseconds)

      testActorSystem.terminate()
    }

    "parse minute interval when configured" in {
      val testActorSystem = ActorSystem("test")
      val job = TestScheduledJob(Map("schedules.TestScheduledJob.interval" -> "15 minutes"), testActorSystem)

      job.interval shouldBe Some(15.minutes)

      testActorSystem.terminate()
    }

    "return None when interval is not configured" in {
      val testActorSystem = ActorSystem("test")
      val job = TestScheduledJob(Map.empty, testActorSystem)

      job.interval shouldBe None

      testActorSystem.terminate()
    }

    "not schedule when enabled but interval is missing" in {
      val testActorSystem = ActorSystem("test")
      val job = TestScheduledJob(
        Map(
          "schedules.TestScheduledJob.enabled" -> true
        ),
        testActorSystem
      )

      // This should not throw an exception, just log
      noException should be thrownBy job.schedule

      testActorSystem.terminate()
    }

    "not schedule when job is disabled" in {
      val testActorSystem = ActorSystem("test")
      val job = TestScheduledJob(
        Map(
          "schedules.TestScheduledJob.enabled" -> false,
          "schedules.TestScheduledJob.interval" -> "1 second"
        ),
        testActorSystem
      )

      // This should not throw an exception, just log
      noException should be thrownBy job.schedule

      testActorSystem.terminate()
    }
  }
}




