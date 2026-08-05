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

import java.time._
import java.time.temporal.ChronoUnit
import scala.annotation.tailrec

sealed trait IntRule {
  def matches(v: Int): Boolean
}
final case class AnyRule() extends IntRule {
  override def matches(v: Int): Boolean = true
}
final case class ExactRule(v: Int) extends IntRule {
  override def matches(x: Int): Boolean = x == v
}
final case class ListRule(values: Set[Int]) extends IntRule {
  override def matches(x: Int): Boolean = values.contains(x)
}
final case class RangeRule(start: Int, end: Int) extends IntRule {
  override def matches(x: Int): Boolean = x >= start && x <= end
}
final case class StepRule(start: Int, step: Int) extends IntRule {
  override def matches(x: Int): Boolean = x >= start && ((x - start) % step == 0)
}

final case class CronSpec(
                           seconds: Set[Int],
                           minutes: IntRule,
                           hours: IntRule,
                           daysOfWeek: Option[Set[DayOfWeek]]
                         ) {
  def matches(dt: ZonedDateTime): Boolean = {
    seconds.contains(dt.getSecond) &&
      minutes.matches(dt.getMinute) &&
      hours.matches(dt.getHour) &&
      daysOfWeek.forall(_.contains(dt.getDayOfWeek))
  }

  /** Find next run strictly after `from`. */
  def nextAfter(from: ZonedDateTime, maxSearchDays: Int = 400): ZonedDateTime = {
    val end = from.plusDays(maxSearchDays.toLong)

    @tailrec
    def loop(cursor: ZonedDateTime): ZonedDateTime = {
      if (cursor.isAfter(end)) {
        throw new IllegalArgumentException(s"No next run found within $maxSearchDays days for cron: $this")
      }
      if (matches(cursor)) cursor else loop(cursor.plusSeconds(1))
    }

    loop(from.plusSeconds(1).truncatedTo(ChronoUnit.SECONDS))
  }
}

object CronExpressionParser {
  private val dowMap: Map[String, DayOfWeek] = Map(
    "MON" -> DayOfWeek.MONDAY,
    "TUE" -> DayOfWeek.TUESDAY,
    "WED" -> DayOfWeek.WEDNESDAY,
    "THU" -> DayOfWeek.THURSDAY,
    "FRI" -> DayOfWeek.FRIDAY,
    "SAT" -> DayOfWeek.SATURDAY,
    "SUN" -> DayOfWeek.SUNDAY
  )

  /**
   * Supports underscore-separated 6-field cron:
   * second_minute_hour_dayOfMonth_month_dayOfWeek
   *
   * Examples:
   * 0_0/3_5-23_?_*_MON-FRI
   * 0_0_9_?_*_*
   * 50_0/2_*_?_*_*
   */
  def parse(raw: String): CronSpec = {
    val expr = raw.replace('_', ' ').trim
    val parts = expr.split("\\s+")
    require(parts.length == 6, s"Expected 6 fields, got ${parts.length}: $raw")

    val secondField = parts(0)
    val minuteField = parts(1)
    val hourField = parts(2)
    val dayOfMonthField = parts(3)
    val monthField = parts(4)
    val dayOfWeekField = parts(5)

    require(dayOfMonthField == "?" || dayOfMonthField == "*",
      s"Unsupported day-of-month '$dayOfMonthField' in: $raw")
    require(monthField == "*", s"Unsupported month '$monthField' in: $raw")

    CronSpec(
      seconds = parseSecond(secondField),
      minutes = parseIntRule(minuteField, 0, 59, "minute"),
      hours = parseIntRule(hourField, 0, 23, "hour"),
      daysOfWeek = parseDayOfWeek(dayOfWeekField)
    )
  }

  private def parseSecond(s: String): Set[Int] = {
    val v = toInt(s, "second")
    require(v >= 0 && v <= 59, s"second out of range [0,59]: $s")
    Set(v)
  }

  private def parseIntRule(s: String, min: Int, max: Int, field: String): IntRule = {
    if (s == "*") {
      AnyRule()
    } else if (s.contains("/")) {
      val arr = s.split("/", 2)
      require(arr.length == 2, s"Invalid $field step format: $s")
      val start = toIntInRange(arr(0), min, max, field)
      val step = toInt(arr(1), field)
      require(step > 0, s"$field step must be > 0: $s")
      StepRule(start, step)
    } else if (s.contains(",")) {
      ListRule(s.split(",").map(v => toIntInRange(v, min, max, field)).toSet)
    } else if (s.contains("-")) {
      val arr = s.split("-", 2)
      require(arr.length == 2, s"Invalid $field range format: $s")
      val start = toIntInRange(arr(0), min, max, field)
      val end = toIntInRange(arr(1), min, max, field)
      require(start <= end, s"Invalid $field range, start > end: $s")
      RangeRule(start, end)
    } else {
      ExactRule(toIntInRange(s, min, max, field))
    }
  }

  private def parseDayOfWeek(s: String): Option[Set[DayOfWeek]] = {
    if (s == "*" || s == "?") {
      None
    } else if (s.contains("-")) {
      val arr = s.split("-", 2)
      require(arr.length == 2, s"Invalid day-of-week range format: $s")
      val start = parseDow(arr(0))
      val end = parseDow(arr(1))
      val all = DayOfWeek.values().toList
      val startIdx = all.indexOf(start)
      val endIdx = all.indexOf(end)
      require(startIdx <= endIdx, s"Unsupported wrap-around day-of-week range: $s")
      Some(all.slice(startIdx, endIdx + 1).toSet)
    } else if (s.contains(",")) {
      Some(s.split(",").map(parseDow).toSet)
    } else {
      Some(Set(parseDow(s)))
    }
  }

  private def parseDow(token: String): DayOfWeek = {
    dowMap.getOrElse(token.toUpperCase, throw new IllegalArgumentException(s"Invalid day-of-week: $token"))
  }

  private def toInt(s: String, field: String): Int = {
    s.toIntOption.getOrElse(throw new IllegalArgumentException(s"Invalid $field value: $s"))
  }

  private def toIntInRange(s: String, min: Int, max: Int, field: String): Int = {
    val v = toInt(s, field)
    require(v >= min && v <= max, s"$field out of range [$min,$max]: $s")
    v
  }
}