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

package uk.gov.hmrc.eacdfileprocessor.helper

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.{Level, Logger as LogbackLogger}
import ch.qos.logback.core.read.ListAppender
import org.scalatest.Assertion
import play.api.LoggerLike
import play.api.test.Helpers.await
import play.api.test.{DefaultAwaitTimeout, FutureAwaits}

import java.util.concurrent.TimeUnit
import scala.concurrent.Future
import scala.jdk.CollectionConverters.*

trait AssertionHelpers extends FutureAwaits with DefaultAwaitTimeout {

  def withCaptureOfLoggingFrom(logger: LoggerLike)(body: (=> List[ILoggingEvent]) => Unit): Unit = {
    val logbackLogger = logger.logger.asInstanceOf[LogbackLogger]
    val appender = new ListAppender[ILoggingEvent]()
    appender.setContext(logbackLogger.getLoggerContext)
    appender.start()
    logbackLogger.addAppender(appender)
    logbackLogger.setLevel(Level.toLevel("ALL"))
    logbackLogger.setAdditive(true)
    body(appender.list.asScala.toList)
  }

  def awaitAndAssert[T](methodUnderTest: => Future[T])(assertions: T => Assertion): Assertion = {
    assertions(await(methodUnderTest, 60, TimeUnit.SECONDS))
  }
}
