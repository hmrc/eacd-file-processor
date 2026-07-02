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

package uk.gov.hmrc.eacdfileprocessor.models

import uk.gov.hmrc.eacdfileprocessor.helper.{TestData, TestSupport}

class DetailsSpec extends TestSupport with TestData {
  "Details getFileName" should {
    "return correct file name if it's UploadedSuccessfully" in {
      val actual = Details.getFileName(successfulUploadedDetails)
      actual mustBe "bulk-de-enrol.csv"
    }
    "return empty file name if it's UploadedFailed" in {
      val actual = Details.getFileName(failedFileDetails)
      actual mustBe ""
    }
  }
}
