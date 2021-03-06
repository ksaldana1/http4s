package com.example.http4s

import _root_.io.circe.Json
import cats._
import cats.effect.IO
import cats.implicits._
import fs2._
import org.http4s.MediaType._
import org.http4s._
import org.http4s.server._
import org.http4s.circe._
import org.http4s.dsl.io._
import org.http4s.headers._
import org.http4s.server.middleware.authentication.BasicAuth
import org.http4s.twirl._

import scala.concurrent._
import scala.concurrent.duration._

object ExampleService {

  // A Router can mount multiple services to prefixes.  The request is passed to the
  // service with the longest matching prefix.
  def service(implicit ec: ExecutionContext = ExecutionContext.global): HttpService[IO] =
    Router[IO](
      "" -> rootService,
      "/auth" -> authService,
      "/science" -> ScienceExperiments.service
    )

  def rootService(implicit ec: ExecutionContext = ExecutionContext.global) = HttpService[IO] {
    case GET -> Root =>
      // Supports Play Framework template -- see src/main/twirl.
      Ok(html.index())

    case _ -> Root =>
      // The default route result is NotFound. Sometimes MethodNotAllowed is more appropriate.
      MethodNotAllowed()

    case GET -> Root / "ping" =>
      // EntityEncoder allows for easy conversion of types to a response body
      Ok("pong")

    case GET -> Root / "future" =>
      // EntityEncoder allows rendering asynchronous results as well
      Ok(IO.fromFuture(Eval.always(Future("Hello from the future!"))))

    case GET -> Root / "streaming" =>
      // It's also easy to stream responses to clients
      Ok(dataStream(100))

    case req @ GET -> Root / "ip" =>
      // It's possible to define an EntityEncoder anywhere so you're not limited to built in types
      val json = Json.obj("origin" -> Json.fromString(req.remoteAddr.getOrElse("unknown")))
      Ok(json)

    case GET -> Root / "redirect" =>
      // Not every response must be Ok using a EntityEncoder: some have meaning only for specific types
      TemporaryRedirect(uri("/http4s/"))

    case GET -> Root / "content-change" =>
      // EntityEncoder typically deals with appropriate headers, but they can be overridden
      Ok("<h2>This will have an html content type!</h2>")
        .withContentType(Some(`Content-Type`(`text/html`)))

    case req @ GET -> "static" /: path =>
      // captures everything after "/static" into `path`
      // Try http://localhost:8080/http4s/static/nasa_blackhole_image.jpg
      // See also org.http4s.server.staticcontent to create a mountable service for static content
      StaticFile.fromResource(path.toString, Some(req)).getOrElseF(NotFound())

    ///////////////////////////////////////////////////////////////
    //////////////// Dealing with the message body ////////////////
    case req @ POST -> Root / "echo" =>
      // The body can be used in the response
      Ok(req.body).map(_.putHeaders(`Content-Type`(`text/plain`)))

    case GET -> Root / "echo" =>
      Ok(html.submissionForm("echo data"))

    case req @ POST -> Root / "echo2" =>
      // Even more useful, the body can be transformed in the response
      Ok(req.body.drop(6))
        .putHeaders(`Content-Type`(`text/plain`))

    case GET -> Root / "echo2" =>
      Ok(html.submissionForm("echo data"))

    case req @ POST -> Root / "sum" =>
      // EntityDecoders allow turning the body into something useful
      req
        .decode[UrlForm] { data =>
          data.values.get("sum") match {
            case Some(Seq(s, _*)) =>
              val sum = s.split(' ').filter(_.length > 0).map(_.trim.toInt).sum
              Ok(sum.toString)

            case None => BadRequest(s"Invalid data: " + data)
          }
        }
        .handleErrorWith { // We can handle errors using Task methods
          case e: NumberFormatException => BadRequest("Not an int: " + e.getMessage)
        }

    case GET -> Root / "sum" =>
      Ok(html.submissionForm("sum"))

    ///////////////////////////////////////////////////////////////
    ////////////////////// Blaze examples /////////////////////////

    // You can use the same service for GET and HEAD. For HEAD request,
    // only the Content-Length is sent (if static content)
    case GET -> Root / "helloworld" =>
      helloWorldService
    case HEAD -> Root / "helloworld" =>
      helloWorldService

    // HEAD responses with Content-Lenght, but empty content
    case HEAD -> Root / "head" =>
      Ok("").putHeaders(`Content-Length`.unsafeFromLong(1024))

    // Response with invalid Content-Length header generates
    // an error (underflow causes the connection to be closed)
    case GET -> Root / "underflow" =>
      Ok("foo").putHeaders(`Content-Length`.unsafeFromLong(4))

    // Response with invalid Content-Length header generates
    // an error (overflow causes the extra bytes to be ignored)
    case GET -> Root / "overflow" =>
      Ok("foo").putHeaders(`Content-Length`.unsafeFromLong(2))

    ///////////////////////////////////////////////////////////////
    //////////////// Form encoding example ////////////////////////
    case GET -> Root / "form-encoded" =>
      Ok(html.formEncoded())

    case req @ POST -> Root / "form-encoded" =>
      // EntityDecoders return a Task[A] which is easy to sequence
      req.decode[UrlForm] { m =>
        val s = m.values.mkString("\n")
        Ok(s"Form Encoded Data\n$s")
      }

    ///////////////////////////////////////////////////////////////
    //////////////////////// Server Push //////////////////////////
    /*
  case req @ GET -> Root / "push" =>
    // http4s intends to be a forward looking library made with http2.0 in mind
    val data = <html><body><img src="image.jpg"/></body></html>
    Ok(data)
      .withContentType(Some(`Content-Type`(`text/html`)))
      .push("/image.jpg")(req)
     */

    case req @ GET -> Root / "image.jpg" =>
      StaticFile
        .fromResource("/nasa_blackhole_image.jpg", Some(req))
        .getOrElseF(NotFound())

    ///////////////////////////////////////////////////////////////
    //////////////////////// Multi Part //////////////////////////
    /* TODO fs2 port
    case req @ GET -> Root / "form" =>
            println("FORM")
      Ok(html.form())

    case req @ POST -> Root / "multipart" =>
      println("MULTIPART")
      req.decode[Multipart] { m =>
        Ok(s"""Multipart Data\nParts:${m.parts.length}\n${m.parts.map { case f: Part => f.name }.mkString("\n")}""")
      }
   */
  }

  val scheduler = Scheduler.allocate[IO](corePoolSize = 1).map(_._1).unsafeRunSync()

  def helloWorldService: IO[Response[IO]] = Ok("Hello World!")

  // This is a mock data source, but could be a Process representing results from a database
  def dataStream(n: Int)(implicit ec: ExecutionContext): Stream[IO, String] = {
    val interval = 100.millis
    val stream =
      scheduler
        .awakeEvery[IO](interval)
        .map(_ => s"Current system time: ${System.currentTimeMillis()} ms\n")
        .take(n.toLong)

    Stream.emit(s"Starting $interval stream intervals, taking $n results\n\n") ++ stream
  }

  // Services can be protected using HTTP authentication.
  val realm = "testrealm"

  def authStore(creds: BasicCredentials) =
    if (creds.username == "username" && creds.password == "password") IO.pure(Some(creds.username))
    else IO.pure(None)

  // An AuthedService[A] is a Service[(A, Request), Response] for some
  // user type A.  `BasicAuth` is an auth middleware, which binds an
  // AuthedService to an authentication store.
  val basicAuth = BasicAuth(realm, authStore)
  def authService: HttpService[IO] =
    basicAuth(AuthedService[IO, String] {
      // AuthedServices look like Services, but the user is extracted with `as`.
      case req @ GET -> Root / "protected" as user =>
        Ok(s"This page is protected using HTTP authentication; logged in as $user")
    })
}
