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

  val appName: String            = config.get[String]("appName")
  val timeToLive: String         = getString("time-to-live.time")
  val internalAuthService: String = servicesConfig.baseUrl("internal-auth")
  val internalAuthToken: String  = getString("internal-auth.token")

  /** Base URL for the enrolment-store-proxy service (resolved from service config). */
  val enrolmentStoreProxyBaseUrl: String = servicesConfig.baseUrl("enrolment-store-proxy")
  
  /** Maximum number of simultaneous requests to enrolment-store-proxy. */
  val maxConcurrentEnrolmentStoreProxyRequests: Int =
    config.getOptional[Int]("throttle.enrolment-store-proxy.max-concurrent").getOrElse(5)


  private def getString(key: String): String =
    config.getOptional[String](key).filter(!_.isBlank)
      .getOrElse(throw new RuntimeException(s"Could not find config key '$key'"))
}
