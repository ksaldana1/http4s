package com.example.http4s.blaze

import java.util.concurrent.Executors

import cats.effect._
import fs2._
import org.http4s._
import org.http4s.dsl.io._
import org.http4s.server.blaze.BlazeBuilder
import org.http4s.server.websocket._
import org.http4s.util.{StreamApp, _}
import org.http4s.websocket.WebsocketBits._

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

object BlazeWebSocketExample extends StreamApp[IO] {
  val scheduler = Scheduler.allocate[IO](corePoolSize = 2).unsafeRunSync()._1
  val threadFactory = threads.threadFactory(name = l => s"worker-$l", daemon = true)
  implicit val ec = ExecutionContext.fromExecutor(Executors.newFixedThreadPool(8, threadFactory))

  val route = HttpService[IO] {
    case GET -> Root / "hello" =>
      Ok("Hello world.")

    case GET -> Root / "ws" =>
      val toClient: Stream[IO, WebSocketFrame] =
        scheduler.awakeEvery[IO](1.seconds).map(d => Text(s"Ping! $d"))
      val fromClient: Sink[IO, WebSocketFrame] = _.evalMap { (ws: WebSocketFrame) =>
        ws match {
          case Text(t, _) => IO(println(t))
          case f => IO(println(s"Unknown type: $f"))
        }
      }
      WS(toClient, fromClient)

    case GET -> Root / "wsecho" =>
      val queue = async.unboundedQueue[IO, WebSocketFrame]
      val echoReply: Pipe[IO, WebSocketFrame, WebSocketFrame] = _.collect {
        case Text(msg, _) => Text("You sent the server: " + msg)
        case _ => Text("Something new")
      }

      queue.flatMap { q =>
        val d = q.dequeue.through(echoReply)
        val e = q.enqueue
        WS(d, e)
      }
  }

  def stream(args: List[String], requestShutdown: IO[Unit]) =
    BlazeBuilder[IO]
      .bindHttp(8080)
      .withWebSockets(true)
      .mountService(route, "/http4s")
      .serve
}
