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

package uk.gov.hmrc.eacdfileprocessor.services

import play.api.Logging
import uk.gov.hmrc.eacdfileprocessor.config.AppConfig
import uk.gov.hmrc.eacdfileprocessor.connectors.Sec0Connector
import uk.gov.hmrc.http.HeaderCarrier

import java.time.{Clock, Instant}
import java.util.concurrent.atomic.AtomicReference
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class AgentServiceCache @Inject()(sec0Connector: Sec0Connector, appConfig: AppConfig, clock: Clock)(using ExecutionContext) extends Logging {

  private case class CacheState(serviceKeys: Set[String], refreshedAt: Instant)

  private val state = new AtomicReference[Option[CacheState]](None)

  def getAgentServices()(using HeaderCarrier): Future[Set[String]] = {
    val now = Instant.now(clock)
    state.get() match {
      case Some(cache) if cache.refreshedAt.plusSeconds(appConfig.sec0CacheRefreshHours.toLong * 3600).isAfter(now) =>
        Future.successful(cache.serviceKeys)
      case _ =>
        sec0Connector.getAgentServiceKeys().map { services =>
          state.set(Some(CacheState(services, now)))
          services
        }
    }
  }
}

