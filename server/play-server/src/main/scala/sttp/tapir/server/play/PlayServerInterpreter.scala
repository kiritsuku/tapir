package sttp.tapir.server.play

import akka.stream.Materializer
import akka.util.ByteString
import play.api.http.HttpEntity
import play.api.mvc.{ActionBuilder, AnyContent, Handler, RawBuffer, Request, RequestHeader, ResponseHeader, Result}
import play.api.routing.Router.Routes
import sttp.capabilities.WebSockets
import sttp.capabilities.akka.AkkaStreams
import sttp.monad.FutureMonad
import sttp.tapir.{DecodeResult, Endpoint, EndpointIO, EndpointInput}
import sttp.tapir.server.ServerDefaults.StatusCodes
import sttp.tapir.server.{DecodeFailureContext, DecodeFailureHandling, ServerDefaults, ServerEndpoint}
import sttp.tapir.server.internal.{DecodeBody, DecodeInputs, DecodeInputsResult, InputValues, InputValuesResult}

import java.nio.charset.Charset
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.reflect.ClassTag

trait PlayServerInterpreter {
  def toRoute[I, E, O](e: Endpoint[I, E, O, AkkaStreams with WebSockets])(
      logic: I => Future[Either[E, O]]
  )(implicit mat: Materializer, serverOptions: PlayServerOptions): Routes = {
    toRoute(e.serverLogic(logic))
  }

  def toRouteRecoverErrors[I, E, O](e: Endpoint[I, E, O, AkkaStreams with WebSockets])(logic: I => Future[O])(implicit
      eIsThrowable: E <:< Throwable,
      eClassTag: ClassTag[E],
      mat: Materializer,
      serverOptions: PlayServerOptions
  ): Routes = {
    toRoute(e.serverLogicRecoverErrors(logic))
  }

  def toRoute[I, E, O](
      e: ServerEndpoint[I, E, O, AkkaStreams with WebSockets, Future]
  )(implicit mat: Materializer, serverOptions: PlayServerOptions): Routes = {
    implicit val ec: ExecutionContextExecutor = mat.executionContext
    implicit val monad: FutureMonad = new FutureMonad()

    def valueToResponse(value: Any): Future[Result] = {
      val i = value.asInstanceOf[I]
      e.logic(monad)(i)
        .map {
          case Right(result) =>
            serverOptions.logRequestHandling.requestHandled(e.endpoint, ServerDefaults.StatusCodes.success.code)(serverOptions.logger)
            OutputToPlayResponse(ServerDefaults.StatusCodes.success, e.output, result)
          case Left(err) =>
            serverOptions.logRequestHandling.requestHandled(e.endpoint, ServerDefaults.StatusCodes.error.code)(serverOptions.logger)
            OutputToPlayResponse(ServerDefaults.StatusCodes.error, e.errorOutput, err)
        }
    }
    def handleDecodeFailure(
        e: Endpoint[_, _, _, _],
        input: EndpointInput[_],
        failure: DecodeResult.Failure
    ): Result = {
      val decodeFailureCtx = DecodeFailureContext(input, failure, e)
      val handling = serverOptions.decodeFailureHandler(decodeFailureCtx)
      handling match {
        case DecodeFailureHandling.NoMatch =>
          serverOptions.logRequestHandling.decodeFailureNotHandled(e, decodeFailureCtx)(serverOptions.logger)
          Result(header = ResponseHeader(StatusCodes.error.code), body = HttpEntity.NoEntity)
        case DecodeFailureHandling.RespondWithResponse(output, value) =>
          serverOptions.logRequestHandling.decodeFailureNotHandled(e, decodeFailureCtx)(serverOptions.logger)
          OutputToPlayResponse(ServerDefaults.StatusCodes.error, output, value)
      }
    }

    val decodeBody = new DecodeBody[Request[RawBuffer], Future] {
      override def rawBody[R](request: Request[RawBuffer], body: EndpointIO.Body[R, _]): Future[R] = new PlayRequestToRawBody(serverOptions)
        .apply(
          body.bodyType,
          request.charset.map(Charset.forName),
          request,
          request.body.asBytes().getOrElse(ByteString.apply(java.nio.file.Files.readAllBytes(request.body.asFile.toPath)))
        )
    }

    val res = new PartialFunction[RequestHeader, Handler] {
      override def isDefinedAt(x: RequestHeader): Boolean = {
        val decodeInputResult = DecodeInputs(e.input, new PlayDecodeInputContext(x, 0, serverOptions))
        val handlingResult = decodeInputResult match {
          case DecodeInputsResult.Failure(input, failure) =>
            val decodeFailureCtx = DecodeFailureContext(input, failure, e.endpoint)
            serverOptions.logRequestHandling.decodeFailureNotHandled(e.endpoint, decodeFailureCtx)(serverOptions.logger)
            serverOptions.decodeFailureHandler(decodeFailureCtx) != DecodeFailureHandling.noMatch
          case DecodeInputsResult.Values(_, _) => true
        }
        handlingResult
      }

      override def apply(v1: RequestHeader): Handler = {
        serverOptions.defaultActionBuilder.async(serverOptions.playBodyParsers.raw) { request =>
          decodeBody(request, DecodeInputs(e.input, new PlayDecodeInputContext(v1, 0, serverOptions))).flatMap {
            case values: DecodeInputsResult.Values =>
              InputValues(e.input, values) match {
                case InputValuesResult.Value(params, _)        => valueToResponse(params.asAny)
                case InputValuesResult.Failure(input, failure) => Future.successful(handleDecodeFailure(e.endpoint, input, failure))
              }
            case DecodeInputsResult.Failure(input, failure) =>
              Future.successful(handleDecodeFailure(e.endpoint, input, failure))
          }
        }
      }
    }
    res
  }

  def toRoute[I, E, O](
      serverEndpoints: List[ServerEndpoint[_, _, _, AkkaStreams with WebSockets, Future]]
  )(implicit mat: Materializer, serverOptions: PlayServerOptions): Routes = {
    serverEndpoints
      .map(toRoute(_))
      .reduce((a: Routes, b: Routes) => a.orElse(b))
  }

  implicit def actionBuilderFromPlayServerOptions(implicit playServerOptions: PlayServerOptions): ActionBuilder[Request, AnyContent] =
    playServerOptions.defaultActionBuilder
}

object PlayServerInterpreter extends PlayServerInterpreter
