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

package uk.gov.hmrc.eacdfileprocessor.config

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.time.{DayOfWeek, ZoneId, ZonedDateTime}

class CronExpressionParserSpec extends AnyWordSpec with Matchers {

  private val zone = ZoneId.of("UTC")

  "CronExpressionParser.parse" should {
    "parse all production cron examples" in {
      val exprs = Seq(
        "0_0/3_5-23_?_*_MON-FRI",
        "0_0/3_5-23_?_*_SAT,SUN",
        "0_0_19_?_*_*",
        "0_0_1,6,18_?_*_*",
        "50_0/2_*_?_*_*",
        "0_0_9_?_*_*",
        "10_0/5_*_?_*_THU"
      )

      exprs.foreach(e => noException should be thrownBy CronExpressionParser.parse(e))
    }
  }

  "CronSpec.nextAfter" should {
    "support fixed daily time at 09:00:00" in {
      val spec = CronExpressionParser.parse("0_0_9_?_*_*")
      val from = ZonedDateTime.of(2026, 7, 30, 8, 59, 59, 0, zone)

      val next = spec.nextAfter(from)

      next.getHour shouldBe 9
      next.getMinute shouldBe 0
      next.getSecond shouldBe 0
    }

    "respect weekday interval and hour window" in {
      val spec = CronExpressionParser.parse("0_0/3_5-23_?_*_MON-FRI")
      val from = ZonedDateTime.of(2026, 7, 31, 4, 59, 58, 0, zone) // Friday

      val next = spec.nextAfter(from)

      next.getDayOfWeek shouldBe DayOfWeek.FRIDAY
      next.getHour shouldBe 5
      next.getMinute shouldBe 0
      next.getSecond shouldBe 0
    }

    "respect weekends" in {
      val spec = CronExpressionParser.parse("0_0/3_5-23_?_*_SAT,SUN")
      val from = ZonedDateTime.of(2026, 8, 1, 4, 59, 58, 0, zone) // Saturday

      val next = spec.nextAfter(from)

      next.getDayOfWeek shouldBe DayOfWeek.SATURDAY
      next.getHour shouldBe 5
      next.getMinute shouldBe 0
      next.getSecond shouldBe 0
    }

    "respect Thursday-only schedule every 5 minutes at second 10" in {
      val spec = CronExpressionParser.parse("10_0/5_*_?_*_THU")
      val from = ZonedDateTime.of(2026, 8, 5, 23, 59, 59, 0, zone) // Wednesday

      val next = spec.nextAfter(from)

      next.getDayOfWeek shouldBe DayOfWeek.THURSDAY
      next.getHour shouldBe 0
      next.getMinute shouldBe 0
      next.getSecond shouldBe 10
    }
  }
}
