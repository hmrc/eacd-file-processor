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

import javax.inject.{Inject, Singleton}
import play.api.Configuration
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig

@Singleton
class AppConfig @Inject()(config: Configuration, servicesConfig: ServicesConfig) {

  val appName: String = getString("appName")
  val timeToLive: String = getString("time-to-live.time")
  val internalAuthService: String = servicesConfig.baseUrl("internal-auth")
  val internalAuthToken: String = getString("internal-auth.token")
  val retryInProgressAfter = getInt("work-item.retry-in-progress-after.seconds")
  val workItemTimeToLive = getInt("work-item.ttlInHours")
  val lockingTimeout = getInt("locking.timeoutMinutes")
  val fileExpiryDays = getInt("fileExpiryDays")

  private[config] def getString(key: String): String =
    config.getOptional[String](key).filter(!_.isBlank)
      .getOrElse(throw new RuntimeException(s"Could not find config key '$key'"))

  private[config] def getInt(key: String): Int =
    config.getOptional[Int](key)
      .getOrElse(throw new RuntimeException(s"Could not find config key '$key'"))
}
