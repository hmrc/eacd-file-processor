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

import javax.inject.Singleton

@Singleton
class DeEnrolmentWorkItemValidator {

  private val validActions = Set("principal", "delegated", "both", "agent")

  private def extractServiceKey(enrolmentKey: String): String =
    enrolmentKey.takeWhile(_ != '~')

  def validate(recordDetail: String, agentServices: Set[String]): Option[String] = {
    val columns = recordDetail.split(",", -1).map(_.trim)

    if (columns.length != 2) {
      Some("Row structure invalid")
    } else {
      val serviceKey = extractServiceKey(columns(0))
      val actionType = columns(1).toLowerCase

      if (!validActions.contains(actionType) || !agentServices.contains(serviceKey) && actionType == "agent") {
        Some("Invalid action type")
      } else if (agentServices.contains(serviceKey) && actionType != "agent") {
        Some("Agent principal deallocation must specify 'agent'")
      } else {
        None
      }
    }
  }
}

