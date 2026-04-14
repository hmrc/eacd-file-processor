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

import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito
import org.mockito.Mockito.{times, verify, when}
import play.api.Configuration
import play.api.http.Status.CREATED
import play.api.test.Helpers.{await, defaultAwaitTimeout}
import uk.gov.hmrc.eacdfileprocessor.config.AppConfig
import uk.gov.hmrc.eacdfileprocessor.helper.{TestData, TestSupport}
import uk.gov.hmrc.eacdfileprocessor.repository.FileRepository
import uk.gov.hmrc.http.client.{HttpClientV2, RequestBuilder}
import uk.gov.hmrc.http.{HttpReads, HttpResponse}
import uk.gov.hmrc.objectstore.client.*
import uk.gov.hmrc.objectstore.client.play.PlayObjectStoreClient

import scala.concurrent.Future

class UploadProgressTrackerSpec extends TestSupport with TestData:
  private lazy val mockServicesConfig = mock[uk.gov.hmrc.play.bootstrap.config.ServicesConfig]
  when(mockServicesConfig.baseUrl("internal-auth")).thenReturn("http://localhost:8470")
  when(mockServicesConfig.baseUrl("enrolment-store-proxy")).thenReturn("http://localhost:9877")
  when(mockServicesConfig.baseUrl("internalAuth")).thenReturn("http://localhost:8470")

  private lazy val appConfig = new AppConfig(
    Configuration.from(
      Map(
        "appName"                                        -> "eacd-file-processor",
        "time-to-live.time"                             -> "3",
        "internal-auth.token"                           -> "12345678",
        "throttle.enrolment-store-proxy.max-concurrent" -> 5
      )
    ),
    mockServicesConfig
  )

  private lazy val reference = initiateUploadDetails.reference

  trait Setup {
    val repository         = mock[FileRepository]
    val objectStoreClient  = mock[PlayObjectStoreClient]
    val mockHttpClientV2: HttpClientV2   = Mockito.mock(classOf[HttpClientV2])
    val mockRequestBuilder: RequestBuilder = Mockito.mock(classOf[RequestBuilder])

    val progressTracker = UploadProgressTracker(repository, appConfig, mockHttpClientV2, objectStoreClient)

    when(mockHttpClientV2.post(any())(any())).thenReturn(mockRequestBuilder)
    when(mockRequestBuilder.withBody(any())(any(), any(), any())).thenReturn(mockRequestBuilder)
    when(mockRequestBuilder.execute(any[HttpReads[HttpResponse]], any()))
      .thenReturn(Future.successful(HttpResponse(CREATED, body = "")))
  }
  

  "UploadProgressTracker" should {
    "update failed upload file details" in new Setup {
      when(repository.updateStatusAndDetails(any(), any(), any())).thenReturn(Future.successful(Some(initiateUploadDetails)))
      
      await(progressTracker.registerUploadResult(reference, failedFileDetails))
      verify(repository, times(1)).updateStatusAndDetails(any(), any(), any())
      verify(repository, times(0)).updateStatus(any(), any())
    }
  }