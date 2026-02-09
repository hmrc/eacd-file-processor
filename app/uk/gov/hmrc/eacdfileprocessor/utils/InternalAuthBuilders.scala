/*
 * Copyright 2025 HM Revenue & Customs
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

import play.api.Configuration
import play.api.mvc.Results.{Forbidden, Unauthorized}
import play.api.mvc.*
import uk.gov.hmrc.eacdfileprocessor.models.auth.AuthRequest
import uk.gov.hmrc.http.Authorization
import uk.gov.hmrc.internalauth.client.*
import uk.gov.hmrc.internalauth.client.Retrieval.Username
import uk.gov.hmrc.play.http.HeaderCarrierConverter

import scala.concurrent.{ExecutionContext, Future}

trait InternalAuthBuilders {

  def auth: BackendAuthComponents
  def configuration: Configuration
  def cc: ControllerComponents

  private val permission: Predicate.Permission = Predicate.Permission(Resource(ResourceType("eacd-file-processor"), ResourceLocation("")), IAAction("ADMIN"))

  def authorisedEntity(providedPermission: Predicate.Permission = permission, apiName: String = "default"): ActionBuilder[AuthRequest, AnyContent] = {

    if(configuration.getOptional[Boolean](s"internalAuth.enabled").getOrElse(true)
      && configuration.getOptional[Boolean](s"internalAuth.${apiName}.enabled").getOrElse(true)){
      auth.authorizedAction(
        predicate = providedPermission,
        retrieval = Retrieval.username,
        onUnauthorizedError = Future.successful(Unauthorized("Request was unauthenticated or expired")),
        onForbiddenError = Future.successful(Forbidden("Request was authenticated but failed authorisation predicates"))
      ) andThen {
        new ActionTransformer[({type A[B] = AuthenticatedRequest[B, Retrieval.Username]})#A, ({type A[B] = AuthRequest[B]})#A] {
          override protected def transform[A](request: AuthenticatedRequest[A, Retrieval.Username]):
          Future[AuthRequest[A]] = Future.successful(AuthRequest(request))
          override protected def executionContext: ExecutionContext = cc.executionContext
        }
      }
    }else{
      new ActionBuilder[({type A[B] = AuthRequest[B]})#A, AnyContent] {
        override def parser: BodyParser[AnyContent] = cc.parsers.default
        override protected def executionContext: ExecutionContext = cc.executionContext
        override def invokeBlock[A](request: Request[A], block: AuthRequest[A] => Future[Result]): Future[Result] = {
          val headerCarrier = HeaderCarrierConverter.fromRequestAndSession(request, request.session)
          block(new AuthRequest(request, headerCarrier, Authorization("Bearer 123"), Username("fake user")))
        }
      }
    }
  }

}
