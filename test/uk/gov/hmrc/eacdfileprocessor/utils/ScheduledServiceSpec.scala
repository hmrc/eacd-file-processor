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

package uk.gov.hmrc.eacdfileprocessor.utils

import org.scalatestplus.play.PlaySpec
import play.api.test.Helpers.{await, defaultAwaitTimeout}
import uk.gov.hmrc.eacdfileprocessor.scheduler.ScheduledService
import uk.gov.hmrc.eacdfileprocessor.services.LockResponse

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{ExecutionContext, Future}

class ScheduledServiceSpec extends PlaySpec {
  "a scheduled service" should {
    "invoke its corresponding invocation method" when {
      "it derives from scheduled service" in {
        val testScheduledService: ScheduledService[Int] = new ScheduledService[Int] {
          def invoke(using ec: ExecutionContext): Future[Int] = Future.successful(5)
        }

        val res: Int = await(testScheduledService.invoke)
        res mustBe 5
      }

      "it derives from scheduled service with locking" in {
        val testScheduledService: ScheduledService[Left[Int, Nothing]] = new ScheduledService[Left[Int, Nothing]] {
          def invoke(using ec: ExecutionContext): Future[Left[Int, Nothing]] = Future.successful(Left(5))
        }

        val Left(value): Either[Int, LockResponse] = await(testScheduledService.invoke)
        value mustBe 5
      }
    }
  }
}