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

package uk.gov.hmrc.eacdfileprocessor

import org.mockito.Mockito.{never, verify, when}
import play.api.inject.Injector
import play.api.{Application, Configuration}
import uk.gov.hmrc.eacdfileprocessor.helper.TestSupport
import uk.gov.hmrc.eacdfileprocessor.scheduler.jobs.FileWorkItemPullJob

class SchedulerStartupSpec extends TestSupport {

  "SchedulerStartup" should {

    "start FileWorkItemPullJob when scheduler is enabled" in {
      val app = mock[Application]
      val injector = mock[Injector]
      val job = mock[FileWorkItemPullJob]

      when(app.configuration).thenReturn(Configuration.from(Map("schedules.FileWorkItemPullJob.enabled" -> true)))
      when(app.injector).thenReturn(injector)
      when(injector.instanceOf[FileWorkItemPullJob]).thenReturn(job)

      new SchedulerStartup(app)

      verify(injector).instanceOf[FileWorkItemPullJob]
    }

    "not start FileWorkItemPullJob when scheduler is disabled" in {
      val app = mock[Application]
      val injector = mock[Injector]

      when(app.configuration).thenReturn(Configuration.from(Map("schedules.FileWorkItemPullJob.enabled" -> false)))
      when(app.injector).thenReturn(injector)

      new SchedulerStartup(app)

      verify(injector, never()).instanceOf[FileWorkItemPullJob]
    }

    "swallow startup exceptions when scheduler is enabled" in {
      val app = mock[Application]
      val injector = mock[Injector]

      when(app.configuration).thenReturn(Configuration.from(Map("schedules.FileWorkItemPullJob.enabled" -> true)))
      when(app.injector).thenReturn(injector)
      when(injector.instanceOf[FileWorkItemPullJob]).thenThrow(new RuntimeException("boom"))

      noException shouldBe thrownBy(new SchedulerStartup(app))
    }
  }
}

