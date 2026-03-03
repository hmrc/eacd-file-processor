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

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.mockito.MockitoSugar
import play.api.Configuration
import play.api.mvc.ControllerComponents
import uk.gov.hmrc.internalauth.client.BackendAuthComponents

class InternalAuthBuildersSpec extends AnyWordSpec with Matchers with MockitoSugar {
  "InternalAuthBuilders" should {
    "provide a default ActionBuilder when internalAuth is disabled" in {
      val config: Configuration = Configuration("internalAuth.enabled" -> false)
      val cc: ControllerComponents = mock[ControllerComponents]
      val authStub: BackendAuthComponents = mock[BackendAuthComponents]
      val builder: InternalAuthBuilders = new InternalAuthBuilders {
        override def auth: BackendAuthComponents = authStub
        override def configuration: Configuration = config
        override def cc: ControllerComponents = cc
      }
      val actionBuilder = builder.authorisedEntity()
      actionBuilder should not be null
    }
  }
}
