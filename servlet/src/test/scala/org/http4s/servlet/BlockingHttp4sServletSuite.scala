/*
 * Copyright 2013 http4s.org
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

package org.http4s
package servlet

import cats.effect.IO
import cats.effect.Resource
import cats.effect.kernel.Temporal
import cats.effect.std.Dispatcher
import cats.syntax.all._
import munit.CatsEffectSuite
import org.http4s.dsl.io._
import org.http4s.syntax.all._

import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import scala.concurrent.duration._
import scala.io.Source

class BlockingHttp4sServletSuite extends CatsEffectSuite {

  private lazy val service = HttpRoutes
    .of[IO] {
      case GET -> Root / "simple" =>
        Ok("simple")
      case req @ POST -> Root / "echo" =>
        Ok(req.body)
      case GET -> Root / "shifted" =>
        // Wait for a bit to make sure we lose the race
        Temporal[IO].sleep(50.milli) *> Ok("shifted")
    }
    .orNotFound

  private val servletServer = ResourceFixture(
    Dispatcher.parallel[IO].flatMap(d => TestEclipseServer(servlet(d)))
  )

  private def get(serverPort: Int, path: String): IO[String] =
    Resource
      .make(IO.blocking(Source.fromURL(new URL(s"http://127.0.0.1:$serverPort/$path"))))(source =>
        IO(source.close())
      )
      .use { source =>
        IO.blocking(source.getLines().mkString)
      }

  private def post(serverPort: Int, path: String, body: String): IO[String] =
    IO {
      val url = new URL(s"http://127.0.0.1:$serverPort/$path")
      val conn = url.openConnection().asInstanceOf[HttpURLConnection]
      val bytes = body.getBytes(StandardCharsets.UTF_8)
      conn.setRequestMethod("POST")
      conn.setRequestProperty("Content-Length", bytes.size.toString)
      conn.setDoOutput(true)
      conn.getOutputStream.write(bytes)
      conn
    }.flatMap { conn =>
      Resource
        .make(
          IO.blocking(Source.fromInputStream(conn.getInputStream, StandardCharsets.UTF_8.name))
        )(source => IO(source.close()))
        .use { source =>
          IO.blocking(source.getLines().mkString)
        }
    }

  servletServer.test("Http4sBlockingServlet handle GET requests") { server =>
    get(server, "simple").assertEquals("simple")
  }

  servletServer.test("Http4sBlockingServlet handle POST requests") { server =>
    post(server, "echo", "input data").assertEquals("input data")
  }

  servletServer.test("Http4sBlockingServlet work for shifted IO") { server =>
    get(server, "shifted").assertEquals("shifted")
  }

  private def servlet(dispatcher: Dispatcher[IO]) =
    BlockingHttp4sServlet.builder[IO](service, dispatcher).build
}
