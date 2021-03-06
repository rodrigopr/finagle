package com.twitter.finagle.httpx.filter

import com.twitter.finagle.{Service, SimpleFilter}
import com.twitter.finagle.httpx.{Request, Response, Status}
import com.twitter.util.Future


/**
 * Validate request filter:
 *   400 Bad Request is the request is /bad-http-request - Finagle sets this if the
 *      request is malformed.
 *   400 Bad Request if the parameters are invalid.
 *
 * The classic Http codec does this automatically.  RichHttp does not (because maybe
 * you want to log or count this).
 */
class ValidateRequestFilter[REQUEST <: Request]
  extends SimpleFilter[REQUEST, Response] {

  def apply(request: REQUEST, service: Service[REQUEST, Response]): Future[Response] = {
    if (request.uri != "/bad-http-request" && request.params.isValid) {
      service(request)
    } else {
      val response = request.response
      response.status = Status.BadRequest
      response.clearContent()
      Future.value(response)
    }
  }
}


object ValidateRequestFilter extends ValidateRequestFilter[Request]
