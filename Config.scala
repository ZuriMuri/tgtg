package tgtg

import cats.syntax.all.*
import com.comcast.ip4s.{Host, *}
import com.monovore.decline.{Argument, Opts}
import org.legogroup.woof.LogLevel
import sttp.model.Uri
import tgtg.notification.NotifyConfig

import scala.concurrent.duration.*

object UserId extends NewType[String]
type UserId = UserId.Type

object ApiToken extends NewType[String]
type ApiToken = ApiToken.Type

case class TgtgConfig(refreshToken: ApiToken, userId: UserId)
case class RedisConfig(host: Host)
case class ServerConfig(interval: FiniteDuration)

object Email extends NewType[String]
type Email = Email.Type

trait BaseConfig:
  def log: LogLevel

case class AuthConfig(email: Option[Email], log: LogLevel) extends BaseConfig

case class Config(
    tgtg: TgtgConfig,
    notification: NotifyConfig,
    cronitor: Option[ApiToken],
    redis: Option[RedisConfig],
    log: LogLevel,
    server: Option[ServerConfig]
) extends BaseConfig

object Config:

  import allOpts.*
  val auth =
    Opts.subcommand("auth", "Authenticate with TooGoodToGo to retrieve your credentials (user-id and refresh-token)")(
      (email, log).mapN(AuthConfig.apply)
    )
  val opts = (tgtg, notification, cronitor, redis, log, server).mapN(Config.apply)

  private object allOpts:
    private given Argument[Uri] = Argument.from("url")(
      Uri
        .parse(_)
        .ensureOr(s => show"Url $s must have a scheme (http:// or https://)")(_.scheme.isDefined)
        .ensureOr(s => show"Url $s must have a host")(_.host.isDefined)
        .ensureOr(s => show"Url $s must not be relative")(_.isAbsolute)
        .toValidatedNel
    )
    private given Argument[Host] =
      Argument.from("host")(str => Host.fromString(str).toRight(show"Invalid host $str").toValidatedNel)

    private given Argument[LogLevel] = Argument.from("level")(_.toLowerCase match
      case "debug" => LogLevel.Debug.validNel
      case "info"  => LogLevel.Info.validNel
      case "warn"  => LogLevel.Warn.validNel
      case "error" => LogLevel.Error.validNel
      case other   => show"Invalid log level $other. Should be one of 'debug', 'info', 'warn' or 'error'".invalidNel
    )

    private val refreshHelp =
      "Refresh token for TooGoodToGo. Get it using `tgtg auth` command"
    private val refreshToken =
      Opts.option[ApiToken]("refresh-token", refreshHelp) orElse Opts.env[ApiToken]("TGTG_REFRESH_TOKEN", refreshHelp)

    private val userIdHelp =
      "User id for TooGoodToGo. Get it using `tgtg auth` command"
    val userId = Opts.option[UserId]("user-id", userIdHelp) orElse Opts.env[UserId]("TGTG_USER_ID", userIdHelp)

    private val gotifyHelp = "Gotify token for notifications"
    private val gotifyToken =
      Opts.option[ApiToken]("gotify-token", gotifyHelp) orElse Opts.env[ApiToken]("GOTIFY_TOKEN", gotifyHelp)
    private val gotifyUrlHelp = "Gotify url to send notifications to"
    private val gotifyUrl =
      Opts.option[Uri]("gotify-url", gotifyUrlHelp) orElse Opts.env[Uri]("GOTIFY_URL", gotifyUrlHelp)

    private val gotify = (gotifyToken, gotifyUrl).mapN(NotifyConfig.Gotify.apply)

    private val pushbulletHelp = "Pushbullet token for notifications"
    private val pushbullet = (Opts.option[ApiToken]("pushbullet-token", pushbulletHelp) orElse Opts
      .env[ApiToken]("PUSHBULLET_TOKEN", pushbulletHelp)).map(NotifyConfig.Pushbullet.apply)

    private val pushoverHelp = "Pushover token for notifications"
    private val pushoverToken =
      Opts.option[ApiToken]("pushover-token", pushoverHelp) orElse Opts.env[ApiToken]("PUSHOVER_TOKEN", pushoverHelp)
    private val pushoverUser =
      Opts.option[UserId]("pushover-user", pushoverHelp) orElse Opts.env[UserId]("PUSHOVER_USER", pushoverHelp)

    private val pushover = (pushoverToken, pushoverUser).mapN(NotifyConfig.Pushover.apply)

    val notification: Opts[NotifyConfig] = List(gotify, pushbullet, pushover).reduce(_ orElse _)

    private val cronitorHelp = "Cronitor token for monitoring (optional)"
    val cronitor = (Opts.option[ApiToken]("cronitor-token", cronitorHelp) orElse Opts
      .env[ApiToken]("CRONITOR_TOKEN", cronitorHelp)).orNone

    val tgtg = (refreshToken, userId).mapN(TgtgConfig.apply)

    private val redisHostHelp =
      "Redis host (for storing auth and notification cache). Uses a local cache.json file if not set"
    private val redisHost =
      Opts.option[Host]("redis-host", redisHostHelp) orElse Opts.env[Host]("REDIS_HOST", redisHostHelp)

    val redis = redisHost.map(RedisConfig.apply).orNone

    private val logHelp = "Set the log level (debug, info, warn, error)"
    val log = (
      Opts.flag("debug", "enable debug logging for more information", "d").as(LogLevel.Debug) orElse
        Opts.option[LogLevel]("log-level", logHelp, "l") orElse Opts.env[LogLevel]("LOG_LEVEL", logHelp)
    ).withDefault(LogLevel.Info)

    private val isServer = Opts.flag("server", "Run as a server (don't exit after first run)", "s")
    private val interval =
      Opts.option[FiniteDuration]("interval", "Interval to sleep between runs", "i").withDefault(5.minutes)

    val server = (isServer, interval).mapN((_, interval) => ServerConfig(interval)).orNone

    val email = Opts.option[Email]("email", "Email to use for authentication").orNone
  end allOpts

end Config
