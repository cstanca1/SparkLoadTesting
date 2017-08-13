package com.hortonworks.gc.rest

import akka.actor.Actor
import akka.event.slf4j.SLF4JLogging
import com.hortonworks.gc.domain._
import java.text.{ParseException, SimpleDateFormat}
import java.util.Date

import com.hortonworks.gc.rest.LivyRestClient.StatementError
import com.hortonworks.gc.service.LivyRestClientService
import net.liftweb.json.Serialization._
import net.liftweb.json.{DateFormat, Formats}

import scala.Some
import spray.http._
import spray.httpx.unmarshalling._
import spray.routing._

/**
  * REST Service actor.
  */
class RestServiceActor extends Actor with RestService {

  implicit def actorRefFactory = context

  def receive = runRoute(rest)
}

/**
  * REST Service
  */
trait RestService extends HttpService with SLF4JLogging {

  val livyRestClientService = LivyRestClientService

  implicit val executionContext = actorRefFactory.dispatcher

  implicit val liftJsonFormats = new Formats {
    val dateFormat = new DateFormat {
      val sdf = new SimpleDateFormat("yyyy-MM-dd")

      def parse(s: String): Option[Date] =
        try {
          Some(sdf.parse(s))
        } catch {
          case e: Exception => None
        }

      def format(d: Date): String = sdf.format(d)
    }
  }

  implicit val string2Date = new FromStringDeserializer[Date] {
    def apply(value: String) = {
      val sdf = new SimpleDateFormat("yyyy-MM-dd")
      try Right(sdf.parse(value))
      catch {
        case e: ParseException => {
          Left(
            MalformedContent("'%s' is not a valid Date value" format (value),
                             e))
        }
      }
    }
  }

  implicit val customRejectionHandler = RejectionHandler {
    case rejections =>
      mapHttpResponse { response =>
        response.withEntity(
          HttpEntity(ContentType(MediaTypes.`application/json`),
                     write(Map("error" -> response.entity.asString))))
      } {
        RejectionHandler.Default(rejections)
      }
  }

  val rest = respondWithMediaType(MediaTypes.`application/json`) {
      path("insrun") {
        get {
          parameters('statement.as[String] ?
            ).as(SparkStatement) {
            sparkStatement: SparkStatement  =>
            { ctx: RequestContext =>
              handleRequest(ctx) {
                log.debug("Searching for customers with parameters: %s".format(
                  sparkStatement))
                livyRestClientService.runCommand(sparkStatement)
              }
            }
          }
        }
      } ~
      path("closeConnection" ) { customerId =>
        get { ctx: RequestContext =>
          handleRequest(ctx) {
            log.debug("Finally Closing Connection")
            livyRestClientService.closeConnection
          }
        }
      }

  }

  /**
    * Handles an incoming request and create valid response for it.
    *
    * @param ctx         request context
    * @param successCode HTTP Status code for success
    * @param action      action to perform
    */
  protected def handleRequest(
      ctx: RequestContext,
      successCode: StatusCode = StatusCodes.OK)(action: => Either[_,_]) {
    action match {
      case Left(result: Object) =>
        ctx.complete(successCode, write(result))

      case Right(error: Failure) =>
        ctx.complete(
          error.getStatusCode,
          net.liftweb.json.Serialization.write(Map("error" -> error.message)))

      case _ =>
        ctx.complete(StatusCodes.InternalServerError)
    }
  }
}
