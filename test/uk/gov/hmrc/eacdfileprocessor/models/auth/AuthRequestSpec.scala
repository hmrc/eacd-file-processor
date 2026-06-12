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

package uk.gov.hmrc.eacdfileprocessor.models.auth

import org.mockito.Mockito.when
import org.scalatest.matchers.should.Matchers.shouldBe
import play.api.test.FakeRequest
import uk.gov.hmrc.eacdfileprocessor.helper.TestSupport
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.internalauth.client.{AuthenticatedRequest, Retrieval}

class AuthRequestSpec extends TestSupport {

  "AuthRequest" should {

    "wrap an AuthenticatedRequest via companion apply" in {
      val fakeRequest = FakeRequest("GET", "/auth").withBody("body")
      val hc = HeaderCarrier()
      val authToken = "Bearer test-token"
      val username = Retrieval.Username("user-a")
      val authenticatedRequest = mock[AuthenticatedRequest[String, Retrieval.Username]]

      when(authenticatedRequest.request).thenReturn(fakeRequest)
      when(authenticatedRequest.headerCarrier).thenReturn(hc)
      when(authenticatedRequest.authorizationToken).thenReturn(authToken)
      when(authenticatedRequest.retrieval).thenReturn(username)

      val request = AuthRequest(authenticatedRequest)

      request.request shouldBe fakeRequest
      request.headerCarrier shouldBe hc
      request.authorizationToken.toString shouldBe s"Authorization($authToken)"
      request.retrieval shouldBe username
      request.path shouldBe "/auth"
    }
  }
}






