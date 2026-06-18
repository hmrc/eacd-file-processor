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

  def validate(recordDetail: String, agentServices: Set[String]): Either[String, (String,String)] = {
    val columns = recordDetail.split(",", -1).map(_.trim)

    columns match {
      case Array(enrolmentKey, rawActionType) =>
        val serviceKey = extractServiceKey(enrolmentKey)
        val actionType = rawActionType.trim.toLowerCase

        (validActions.contains(actionType), agentServices.contains(serviceKey), actionType) match {
          case (false, _, _) => Left("Invalid action type")
          case (_, _, "agent") if !agentServices.contains(serviceKey) => Left("Invalid action type")
          case (_, true, actionType) if actionType == "agent" => Left("Agent principal deallocation must specify 'agent'")
          case _ => Right(enrolmentKey, actionType)
        }

      case _ => Left("Row structure invalid")
    }
  }
}

