package sttp.tapir.server.play

import akka.actor.ActorSystem
import cats.data.NonEmptyList
import cats.effect.{IO, Resource}
import play.api.Mode
import play.api.mvc.{Handler, RequestHeader}
import play.api.routing.Router
import play.api.routing.Router.Routes
import play.core.server.{DefaultAkkaHttpServerComponents, ServerConfig}
import sttp.capabilities.akka.AkkaStreams
import sttp.tapir.Endpoint
import sttp.tapir.server.tests.TestServerInterpreter
import sttp.tapir.server.{DecodeFailureHandler, ServerDefaults, ServerEndpoint}
import sttp.tapir.tests.Port

import scala.concurrent.Future
import scala.reflect.ClassTag

class PlayTestServerInterpreter(implicit actorSystem: ActorSystem) extends TestServerInterpreter[Future, AkkaStreams, Router.Routes] {
  import actorSystem.dispatcher

  override def route[I, E, O](
      e: ServerEndpoint[I, E, O, AkkaStreams, Future],
      decodeFailureHandler: Option[DecodeFailureHandler]
  ): Routes = {
    implicit val serverOptions: PlayServerOptions =
      PlayServerOptions.default.copy(decodeFailureHandler = decodeFailureHandler.getOrElse(ServerDefaults.decodeFailureHandler))
    PlayServerInterpreter.toRoute(e)
  }

  override def routeRecoverErrors[I, E <: Throwable, O](e: Endpoint[I, E, O, AkkaStreams], fn: I => Future[O])(implicit
      eClassTag: ClassTag[E]
  ): Routes = {
    PlayServerInterpreter.toRouteRecoverErrors(e)(fn)
  }

  override def server(routes: NonEmptyList[Routes]): Resource[IO, Port] = {
    val components = new DefaultAkkaHttpServerComponents {
      override lazy val serverConfig: ServerConfig = ServerConfig(port = Some(0), address = "127.0.0.1", mode = Mode.Test)
      override lazy val actorSystem: ActorSystem =
        ActorSystem("tapir", defaultExecutionContext = Some(PlayTestServerInterpreter.this.actorSystem.dispatcher))
      override def router: Router =
        Router.from(
          routes.reduce((a: Routes, b: Routes) => {
            val handler: PartialFunction[RequestHeader, Handler] = { case request =>
              a.applyOrElse(request, b)
            }

            handler
          })
        )
    }
    val bind = IO {
      components.server
    }
    Resource.make(bind)(s => IO(s.stop())).map(_.mainAddress.getPort)
  }
}
