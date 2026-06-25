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

package uk.gov.hmrc.eacdfileprocessor.scheduler.jobs

import org.scalatest.matchers.should.Matchers.shouldBe
import play.api.Configuration
import play.api.inject.ApplicationLifecycle
import play.api.test.Helpers.{await, defaultAwaitTimeout}
import uk.gov.hmrc.eacdfileprocessor.helper.TestSupport
import uk.gov.hmrc.eacdfileprocessor.services.DeEnrolmentWorkItemSchedulerService

import java.util.concurrent.{Callable, CompletionStage}
import scala.concurrent.Future

class DeEnrolmentWorkItemPullJobSpec extends TestSupport {

  private class StubLifecycle extends ApplicationLifecycle {
    var stopHookCalls: Int = 0

    override def addStopHook(hook: () => Future[?]): Unit = stopHookCalls += 1

    override def addStopHook(hook: Callable[? <: CompletionStage[?]]): Unit = stopHookCalls += 1

    override def stop(): Future[?] = Future.unit
  }

  "DeEnrolmentWorkItemPullJob" should {

    "initialize with expected defaults and register stop hook" in {
      val lifecycle = StubLifecycle()
      val schedulerService = mock[DeEnrolmentWorkItemSchedulerService]
      val config = Configuration.from(Map("schedules.DeEnrolmentWorkItemPullJob.enabled" -> false))

      val job = DeEnrolmentWorkItemPullJob(config, schedulerService, lifecycle)

      job.jobName shouldBe "DeEnrolmentWorkItemPullJob"
      job.deEnrolmentWorkItemSchedulerService shouldBe schedulerService
      job.interval shouldBe None
      lifecycle.stopHookCalls shouldBe 1

      await(job.actorSystem.terminate())
    }
  }
}



