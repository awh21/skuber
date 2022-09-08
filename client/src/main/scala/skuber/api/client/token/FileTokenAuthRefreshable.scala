package skuber.api.client.token

import org.joda.time.DateTime
import skuber.K8SException
import skuber.api.client.{AuthProviderRefreshableAuth, Status}

import scala.concurrent.duration.{Duration, DurationInt}
import scala.util.{Failure, Success}

final case class FileTokenAuthRefreshable(config: FileTokenConfiguration) extends TokenAuthRefreshable with FileReaderComponent {}

final case class FileTokenConfiguration(
    cachedAccessToken: Option[RefreshableToken],
    tokenPath: Option[String],
    refreshInterval: Duration = 5.minutes,
)

trait TokenAuthRefreshable extends AuthProviderRefreshableAuth { self: ContentReaderComponent =>
  val config: FileTokenConfiguration

  private val refreshInterval: Duration = config.refreshInterval
  @volatile private var cachedToken: Option[RefreshableToken] = config.cachedAccessToken

  private val tokenPath: String = {
    config.tokenPath.getOrElse {
      throw new K8SException(
        Status(reason = Some("token path not found, please provide the token path for refreshing the token"))
      )
    }
  }

  override def name: String = "file-token"
  override def toString: String = """FileTokenAuthRefreshable(accessToken=<redacted>)""".stripMargin

  override def refreshToken: RefreshableToken = {
    val refreshedToken = RefreshableToken(generateToken, DateTime.now.plus(refreshInterval.toMillis))
    cachedToken = Some(refreshedToken)
    refreshedToken
  }

  override def generateToken: String = {
    val maybeToken = contentReader.read(tokenPath)
    maybeToken match {
      case Success(token) => token
      case Failure(e) => throw new K8SException(Status(reason = Option(e.getMessage)))
    }
  }

  override def isTokenExpired(refreshableToken: RefreshableToken): Boolean =
    refreshableToken.expiry.isBefore(System.currentTimeMillis)

  override def accessToken: String = this.synchronized {
    cachedToken match {
      case Some(token) if isTokenExpired(token) =>
        refreshToken.accessToken
      case None =>
        refreshToken.accessToken
      case Some(token) =>
        token.accessToken
    }
  }
}

