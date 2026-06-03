package uk.gov.hmrc.eacdfileprocessor.connectors

import play.api.Logging
import play.api.http.Status.OK
import uk.gov.hmrc.eacdfileprocessor.config.AppConfig
import uk.gov.hmrc.http.{HeaderCarrier, HttpReads, HttpResponse, StringContextOps}
import uk.gov.hmrc.http.client.HttpClientV2

import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}


class EspConnector @Inject()(httpClient: HttpClientV2, appConfig: AppConfig)(using ExecutionContext)  extends Logging {

   //ES1 returns 204 then ES9 is not called and given success code
   //ES1 returns single error count as an error and record the "message" from the error response
   //ES1 returns multiple errors count as an error and record the outer "message" from the error response
   //ES1 returns 200 then return success code and record the "message" from the response
   
   def callES1(enrolmentKey:String)(using HeaderCarrier): Future[HttpResponse] =
     httpClient
       .get(url"${appConfig.enrolmentStoreProxyBaseUrl}/enrolment-store-proxy/enrolment-store/enrolments/$enrolmentKey/groups")
       .execute[HttpResponse]
  
  //ES9 returns a 500 count as an error status code and record the "message" from the error response and return service unavailable 
  // ES9 receives BOTH which is "principal" and "agent" then 2 ES9 calls need to be made
  // ES9 returns 500 on 1 out of the 2 calls then record the record as an error and use the message "Partial processing due to unknown error, review manually" 
  
   def callES9(groupId:String, enrolmentKey:String)(using HeaderCarrier): Future[HttpResponse] =
    httpClient
      .delete(url"${appConfig.enrolmentStoreProxyBaseUrl}/enrolment-store/groups/$groupId/enrolments/$enrolmentKey")
      .execute[HttpResponse]

}
