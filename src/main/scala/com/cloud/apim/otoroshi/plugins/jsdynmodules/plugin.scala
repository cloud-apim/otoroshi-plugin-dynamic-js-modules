package otoroshi_plugins.com.cloud.apim.plugins.jsdynmodules

import akka.stream.alpakka.s3.scaladsl.S3
import akka.stream.{Attributes, Materializer}
import akka.stream.alpakka.s3.{ApiVersion, MemoryBufferType, ObjectMetadata, S3Attributes, S3Settings}
import akka.stream.scaladsl.{Sink, StreamConverters}
import akka.util.ByteString
import com.github.blemale.scaffeine.Scaffeine
import io.otoroshi.wasm4s.scaladsl._
import otoroshi.env.Env
import otoroshi.gateway.Errors
import otoroshi.models.WasmPlugin
import otoroshi.next.models.NgRoute
import otoroshi.next.plugins._
import otoroshi.next.plugins.api._
import otoroshi.next.proxy.NgProxyEngineError
import otoroshi.storage.drivers.inmemory.S3Configuration
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits._
import otoroshi.wasm._
import play.api.libs.json._
import play.api.libs.ws.DefaultWSCookie
import play.api.mvc._
import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, StaticCredentialsProvider}
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.regions.providers.AwsRegionProvider

import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent._
import scala.concurrent.duration._
import scala.util._

object NgTransformerErrorContextHelper {
  def wasmJson(ctx: NgTransformerErrorContext)(implicit env: Env, ec: ExecutionContext): Future[JsValue] = {
    implicit val mat = env.otoroshiMaterializer
    otoroshi.next.utils.JsonHelpers.responseBody(ctx.otoroshiResponse).map { bodyOut =>
      ctx.json.asObject ++ Json.obj(
        "route"               -> ctx.route.json,
        "response_body_bytes" -> bodyOut
      )
    }
  }
}

object NgPluginHttpResponseHelper {
  def fromResult(result: Result): NgPluginHttpResponse = {
    NgPluginHttpResponse(
      status = result.header.status,
      headers = result.header.headers,
      cookies = result.newCookies.map(c => DefaultWSCookie(
        name = c.name,
        value = c.value,
        maxAge = c.maxAge.map(_.toLong),
        path = Option(c.path),
        domain = c.domain,
        secure = c.secure,
        httpOnly = c.httpOnly,
      )),
      body = result.body.dataStream,
    )
  }
}

case class JsModulePluginConfig(runtimeRef: Option[String], module: String, modulePath: String, headers: Map[String, String]) extends NgPluginConfig {
  override def json: JsValue = JsModulePluginConfig.format.writes(this)
  def wasmConfig(): WasmConfig = {
    runtimeRef match {
      case Some(ref) =>  WasmConfig(source = WasmSource(WasmSourceKind.Local, ref, Json.obj()))
      case None => WasmConfig(source = WasmSource(WasmSourceKind.Local, JsModulePlugin.wasmPluginId, Json.obj()))
    }
  }
}

object JsModulePluginConfig {
  val default = JsModulePluginConfig(
    None,
    "none",
    "none",
    Map.empty
  )
  val format = new Format[JsModulePluginConfig] {
    override def writes(o: JsModulePluginConfig): JsValue = Json.obj(
      "runtime_ref" -> o.runtimeRef.map(JsString.apply).getOrElse(JsNull).asValue,
      "module" -> o.module,
      "module_path" -> o.modulePath,
      "headers" -> o.headers,
    )
    override def reads(json: JsValue): JsResult[JsModulePluginConfig] = Try {
      JsModulePluginConfig(
        runtimeRef = json.select("runtime_ref").asOpt[String].filterNot(_.isBlank),
        module = json.select("module").asString,
        modulePath = json.select("module_path").asString,
        headers = json.select("headers").asOpt[Map[String, String]].getOrElse(Map.empty),
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(e) => JsSuccess(e)
    }
  }
}

object JsModulePlugin {
  val wasmPluginId = "wasm-plugin_cloud_apim_dynamic_js_modules_runtime"
}

class JsModulePlugin extends NgAccessValidator with NgRequestTransformer with NgBackendCall {

  override def steps: Seq[NgStep] = Seq(
    NgStep.ValidateAccess,
    NgStep.TransformRequest,
    NgStep.CallBackend,
    NgStep.TransformResponse,
  )
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Wasm, NgPluginCategory.Custom("Cloud APIM"))
  override def visibility: NgPluginVisibility              = NgPluginVisibility.NgUserLand
  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = false
  override def useDelegates: Boolean                       = true
  override def usesCallbacks: Boolean                      = false
  override def transformsRequest: Boolean                  = true
  override def transformsResponse: Boolean                 = true
  override def transformsError: Boolean                    = true
  override def name: String                                = "Cloud APIM - Js Module plugin"
  override def description: Option[String]                 = "Dynamically run Js Modules without the need to compile them before".some
  override def defaultConfigObject: Option[NgPluginConfig] = JsModulePluginConfig.default.some

  override def noJsForm: Boolean = true

  override def jsonDescription(): JsObject = Json.obj(
    "name"          -> name,
    "description"   -> description.map(JsString.apply).getOrElse(JsNull).as[JsValue],
    "defaultConfig" -> defaultConfig.getOrElse(JsNull).as[JsValue],
    "configSchema"  -> configSchema.getOrElse(JsNull).as[JsValue],
    "configFlow"    -> JsArray(configFlow.map(JsString.apply)),
  )

  override def configFlow: Seq[String] = Seq(
    "runtime_ref",
    "module_path",
    "module",
    "headers",
    ""
  )

  override def configSchema: Option[JsObject] = Some(Json.obj(
    "runtime_ref" -> Json.obj(
      "type" -> "string",
      "label" -> "Runtime wasm plugin",
    ),
    "module" -> Json.obj(
      "type" -> "string",
      "label" -> "Module url",
    ),
    "module_path" -> Json.obj(
      "type" -> "string",
      "label" -> "Module internal path",
    ),
    "headers" -> Json.obj(
      "type" -> "object",
      "label" -> "HTTP headers",
    )
  ))

  private val modulesCache = Scaffeine().maximumSize(1000).expireAfterWrite(120.seconds).build[String, String]

  override def start(env: Env): Future[Unit] = {
    implicit val ev = env
    implicit val ec = env.otoroshiExecutionContext
    env.logger.info("[Cloud APIM] the 'Dynamic Js Module' plugin is available !")
    env.datastores.wasmPluginsDataStore.findById(JsModulePlugin.wasmPluginId).flatMap {
      case Some(_) => ().vfuture
      case None => {
        env.datastores.wasmPluginsDataStore.set(WasmPlugin(
          id = JsModulePlugin.wasmPluginId,
          name = "Cloud APIM - Dynamic Js Module Runtime",
          description = "This plugin provides the runtime for the Dynamic Js Module Runtime plugin from Cloud APIM",
          config = WasmConfig(
            source = WasmSource(WasmSourceKind.ClassPath, "wasm/otoroshi-plugin-dynamic-js-modules-runtime.wasm", Json.obj()),
            memoryPages = 60,
            wasi = true,
            allowedHosts = Seq("*"),
            authorizations = WasmAuthorizations().copy(httpAccess = true)
          )
        )).map(_ => ())
      }
    }
  }

  private def s3ClientSettingsAttrs(conf: S3Configuration): Attributes = {
    val awsCredentials = StaticCredentialsProvider.create(
      AwsBasicCredentials.create(conf.access, conf.secret)
    )
    val settings       = S3Settings(
      bufferType = MemoryBufferType,
      credentialsProvider = awsCredentials,
      s3RegionProvider = new AwsRegionProvider {
        override def getRegion: Region = Region.of(conf.region)
      },
      listBucketApiVersion = ApiVersion.ListBucketVersion2
    ).withEndpointUrl(conf.endpoint)
    S3Attributes.settings(settings)
  }

  private def fileContent(key: String, config: S3Configuration)(implicit
                                                                ec: ExecutionContext,
                                                                mat: Materializer
  ): Future[Option[(ObjectMetadata, ByteString)]] = {
    S3.download(config.bucket, key)
      .withAttributes(s3ClientSettingsAttrs(config))
      .runWith(Sink.headOption)
      .map(_.flatten)
      .flatMap { opt =>
        opt
          .map {
            case (source, om) => {
              source.runFold(ByteString.empty)(_ ++ _).map { content =>
                (om, content).some
              }
            }
          }
          .getOrElse(None.vfuture)
      }
  }


  private def pluginNotFound(request: RequestHeader, route: NgRoute, attrs: TypedMap)(implicit env: Env, ec: ExecutionContext): Future[Result] = {
    Errors
      .craftResponseResult(
        "plugin not found !",
        Results.Status(500),
        request,
        None,
        None,
        attrs = attrs,
        maybeRoute = route.some
      )
  }

  private def getDefaultCode(pluginConfig: JsModulePluginConfig)(implicit env: Env, ec: ExecutionContext): Future[String] = {
    s"""'inline module';
       |exports.on_validate = function(ctx) {
       |  return {
       |    result: false,
       |    error: {
       |      status: 500,
       |      headers: {
       |        "Content-Type": "application/json"
       |      },
       |      body_json: { error: "Module '${pluginConfig.modulePath}' cannot be loaded ..." }
       |    }
       |  }
       |};
       |exports.on_request = function(ctx) {
       |  return {
       |    error: true,
       |    status: 500,
       |    headers: {
       |      "Content-Type": "application/json"
       |    },
       |    body_json: { error: "Module '${pluginConfig.modulePath}' cannot be loaded ..." }
       |  }
       |};
       |exports.on_backend_call = function(ctx) {
       |  return {
       |    status: 500,
       |    headers: {
       |      "Content-Type": "application/json"
       |    },
       |    body_json: { error: "Module '${pluginConfig.modulePath}' cannot be loaded ..." }
       |  }
       |};
       |exports.on_response = function(ctx) {
       |  return {
       |    error: true,
       |    status: 500,
       |    headers: {
       |      "Content-Type": "application/json"
       |    },
       |    body_json: { error: "Module '${pluginConfig.modulePath}' cannot be loaded ..." }
       |  }
       |};
       |exports.on_error = function(ctx) {
       |  return {
       |    status: 500,
       |    headers: {
       |      "Content-Type": "application/json"
       |    },
       |    body_json: { error: "Module '${pluginConfig.modulePath}' cannot be loaded ..." }
       |  }
       |};
       |""".stripMargin.vfuture
  }

  private def getCode(pluginConfig: JsModulePluginConfig)(implicit env: Env, ec: ExecutionContext): Future[String] = {
    modulesCache.getIfPresent(pluginConfig.module) match {
      case Some(code) => code.vfuture
      case None => {
        val path = pluginConfig.module
        if (path.startsWith("https://") || path.startsWith("http://")) {
          env.Ws.url(pluginConfig.module)
            .withFollowRedirects(true)
            .withRequestTimeout(30.seconds)
            .withHttpHeaders(pluginConfig.headers.toSeq: _*)
            .get()
            .flatMap { response =>
              if (response.status == 200) {
                modulesCache.put(pluginConfig.module, response.body)
                response.body.vfuture
              } else {
                getDefaultCode(pluginConfig).map { code =>
                  modulesCache.put(pluginConfig.module, code)
                  code
                }
              }
            }
        } else if (path.startsWith("file://")) {
          val file = new File(path.replace("file://", ""), "")
          if (file.exists()) {
            val code = Files.readString(file.toPath)
            modulesCache.put(path, code)
            code.vfuture
          } else {
            getDefaultCode(pluginConfig).map { code =>
              modulesCache.put(pluginConfig.module, code)
              code
            }
          }
        } else if (path.startsWith("'inline module';") || path.startsWith("\"inline module\";")) {
          modulesCache.put(path, path)
          path.vfuture
        } else if (path.startsWith("s3://")) {
          val config = S3Configuration.format.reads(JsObject(pluginConfig.headers.mapValues(_.json))).get
          fileContent(pluginConfig.module.replaceFirst("s3://", ""), config)(env.otoroshiExecutionContext, env.otoroshiMaterializer).flatMap {
            case None => getDefaultCode(pluginConfig).map { code =>
              modulesCache.put(pluginConfig.module, code)
              code
            }
            case Some((_, codeRaw)) => {
              val code = codeRaw.utf8String
              modulesCache.put(path, code)
              code.vfuture
            }
          }
        } else {
          getDefaultCode(pluginConfig).map { code =>
            modulesCache.put(pluginConfig.module, code)
            code
          }
        }
      }
    }
  }

  override def access(ctx: NgAccessContext)(implicit env: Env, ec: ExecutionContext): Future[NgAccess] = {
    val pluginConfig = ctx
      .cachedConfig(internalName)(JsModulePluginConfig.format)
      .getOrElse(JsModulePluginConfig.default)
    val wasmConfig = pluginConfig.wasmConfig()
    getCode(pluginConfig).flatMap { code =>
      ctx.wasmJson.vfuture
        .flatMap(input => {
          env.wasmIntegration.wasmVmFor(wasmConfig).flatMap {
            case None => pluginNotFound(ctx.request, ctx.route, ctx.attrs).map(r => NgAccess.NgDenied(r))
            case Some((vm, localConfig)) =>
              vm.call(
                WasmFunctionParameters.ExtismFuntionCall(
                  "cloud_apim_module_plugin_execute_on_validate",
                  Json.obj(
                    "code" -> code,
                    "request" -> input.select("request").asOpt[JsValue].getOrElse(JsNull).asValue,
                    "snowflake" -> ctx.snowflake,
                    "body" -> input.select("request_body_bytes").asOpt[JsValue].getOrElse(JsNull).asValue,
                    "apikey" -> ctx.apikey.map(_.lightJson).getOrElse(JsNull).asValue,
                    "user" -> ctx.user.map(_.lightJson).getOrElse(JsNull).asValue,
                  ).stringify
                ),
                None
              ).flatMap {
                case Right(res) =>
                  val response = Json.parse(res._1)
                  AttrsHelper.updateAttrs(ctx.attrs, response)
                  val result   = (response \ "result").asOpt[Boolean].getOrElse(false)
                  if (result) {
                    NgAccess.NgAllowed.vfuture
                  } else {
                    val error = (response \ "error").asOpt[JsObject].getOrElse(Json.obj())
                    Errors
                      .craftResponseResult(
                        (error \ "message").asOpt[String].getOrElse("An error occured"),
                        Results.Status((error \ "status").asOpt[Int].getOrElse(403)),
                        ctx.request,
                        None,
                        None,
                        attrs = ctx.attrs,
                        maybeRoute = ctx.route.some
                      )
                      .map(r => NgAccess.NgDenied(r))
                  }
                case Left(value) => NgAccess.NgDenied(Results.BadRequest(value)).vfuture
              }.andThen { case _ =>
                vm.release()
              }
          }
        })
    }
  }

  override def callBackend(ctx: NgbBackendCallContext, delegates: () => Future[Either[NgProxyEngineError, BackendCallResponse]])(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    val pluginConfig = ctx
      .cachedConfig(internalName)(JsModulePluginConfig.format)
      .getOrElse(JsModulePluginConfig.default)
    val wasmConfig = pluginConfig.wasmConfig()
    getCode(pluginConfig).flatMap { code =>
      ctx.wasmJson
        .flatMap(input => {
          env.wasmIntegration.wasmVmFor(wasmConfig).flatMap {
            case None => pluginNotFound(ctx.rawRequest, ctx.route, ctx.attrs).map(r => NgProxyEngineError.NgResultProxyEngineError(r).left)
            case Some((vm, localConfig)) =>
              vm.call(
                WasmFunctionParameters.ExtismFuntionCall(
                  "cloud_apim_module_plugin_execute_on_backend_call",
                  Json.obj(
                    "code" -> code,
                    "request" -> input.select("request").asOpt[JsValue].getOrElse(JsNull).asValue,
                    "snowflake" -> ctx.snowflake,
                    "body" -> input.select("request_body_bytes").asOpt[JsValue].getOrElse(JsNull).asValue,
                    "apikey" -> ctx.apikey.map(_.lightJson).getOrElse(JsNull).asValue,
                    "user" -> ctx.user.map(_.lightJson).getOrElse(JsNull).asValue,
                  ).stringify
                ),
                None
              ).flatMap {
                case Right(res) =>
                  val response = Json.parse(res._1)
                  AttrsHelper.updateAttrs(ctx.attrs, response)
                  val delegatesCall = response.select("delegates_call").asOpt[Boolean].contains(true)
                  if (delegatesCall) {
                    delegates()
                  } else {
                    val body = BodyHelper.extractBodyFrom(response)
                    inMemoryBodyResponse(
                      status = response.select("status").asOpt[Int].getOrElse(200),
                      headers = response
                        .select("headers")
                        .asOpt[Map[String, String]]
                        .getOrElse(Map("Content-Type" -> "application/json")),
                      body = body
                    ).vfuture
                  }
                case Left(value) =>  NgProxyEngineError.NgResultProxyEngineError(Results.BadRequest(value)).leftf
              }.andThen { case _ =>
                vm.release()
              }
          }
        })
    }
  }

  override def transformRequest(ctx: NgTransformerRequestContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpRequest]] = {
    val pluginConfig = ctx
      .cachedConfig(internalName)(JsModulePluginConfig.format)
      .getOrElse(JsModulePluginConfig.default)
    val wasmConfig = pluginConfig.wasmConfig()
    getCode(pluginConfig).flatMap { code =>
      ctx.wasmJson
        .flatMap(input => {
          env.wasmIntegration.wasmVmFor(wasmConfig).flatMap {
            case None => pluginNotFound(ctx.request, ctx.route, ctx.attrs).map(r => Left(r))
            case Some((vm, localConfig)) =>
              vm.call(
                WasmFunctionParameters.ExtismFuntionCall(
                  "cloud_apim_module_plugin_execute_on_request",
                  Json.obj(
                    "code" -> code,
                    "raw_request" -> ctx.rawRequest.json,
                    "otoroshi_request" -> ctx.otoroshiRequest.json,
                    "request" -> input.select("request").asOpt[JsValue].getOrElse(JsNull).asValue,
                    "snowflake" -> ctx.snowflake,
                    "body" -> input.select("request_body_bytes").asOpt[JsValue].getOrElse(JsNull).asValue,
                    "apikey" -> ctx.apikey.map(_.lightJson).getOrElse(JsNull).asValue,
                    "user" -> ctx.user.map(_.lightJson).getOrElse(JsNull).asValue,
                  ).stringify
                ),
                None
              ).map {
                case Right(res) =>
                  val response = Json.parse(res._1)
                  AttrsHelper.updateAttrs(ctx.attrs, response)
                  val body = BodyHelper.extractBodyFromOpt(response)
                  if (response.select("error").asOpt[Boolean].getOrElse(false)) {
                    val status      = response.select("status").asOpt[Int].getOrElse(500)
                    val headers     = (response \ "headers").asOpt[Map[String, String]].getOrElse(Map.empty)
                    val cookies     = WasmUtils.convertJsonPlayCookies(response).getOrElse(Seq.empty)
                    val contentType = headers.getIgnoreCase("Content-Type").getOrElse("application/octet-stream")
                    Results
                      .Status(status)(body.getOrElse(ByteString.empty))
                      .withCookies(cookies: _*)
                      .withHeaders(headers.toSeq: _*)
                      .as(contentType)
                      .left
                  } else {
                    ctx.otoroshiRequest.copy(
                      // TODO: handle client cert chain and backend
                      method = (response \ "method").asOpt[String].getOrElse(ctx.otoroshiRequest.method),
                      url = (response \ "url").asOpt[String].getOrElse(ctx.otoroshiRequest.url),
                      headers =
                        (response \ "headers").asOpt[Map[String, String]].getOrElse(ctx.otoroshiRequest.headers),
                      cookies = WasmUtils.convertJsonCookies(response).getOrElse(ctx.otoroshiRequest.cookies),
                      body = body.map(_.chunks(16 * 1024)).getOrElse(ctx.otoroshiRequest.body)
                    ).right
                  }
                case Left(value) => Results.BadRequest(value).left
              }.andThen { case _ =>
                vm.release()
              }
          }
        })
    }
  }

  override def transformResponse(ctx: NgTransformerResponseContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpResponse]] = {
    val pluginConfig = ctx
      .cachedConfig(internalName)(JsModulePluginConfig.format)
      .getOrElse(JsModulePluginConfig.default)
    val wasmConfig = pluginConfig.wasmConfig()
    getCode(pluginConfig).flatMap { code =>
      ctx.wasmJson
        .flatMap(input => {
          env.wasmIntegration.wasmVmFor(wasmConfig).flatMap {
            case None => pluginNotFound(ctx.request, ctx.route, ctx.attrs).map(r => Left(r))
            case Some((vm, localConfig)) =>
              vm.call(
                WasmFunctionParameters.ExtismFuntionCall(
                  "cloud_apim_module_plugin_execute_on_response",
                  Json.obj(
                    "code" -> code,
                    "raw_response" -> ctx.rawResponse.json,
                    "otoroshi_response" -> ctx.otoroshiResponse.json,
                    "request" -> input.select("request").asOpt[JsValue].getOrElse(JsNull).asValue,
                    "snowflake" -> ctx.snowflake,
                    "body" -> input.select("request_body_bytes").asOpt[JsValue].getOrElse(JsNull).asValue,
                    "apikey" -> ctx.apikey.map(_.lightJson).getOrElse(JsNull).asValue,
                    "user" -> ctx.user.map(_.lightJson).getOrElse(JsNull).asValue,
                  ).stringify
                ),
                None
              ).map {
                case Right(res) =>
                  val response = Json.parse(res._1)
                  AttrsHelper.updateAttrs(ctx.attrs, response)
                  val body = BodyHelper.extractBodyFromOpt(response)
                  if (response.select("error").asOpt[Boolean].getOrElse(false)) {
                    val status      = response.select("status").asOpt[Int].getOrElse(500)
                    val headers     = (response \ "headers").asOpt[Map[String, String]].getOrElse(Map.empty)
                    val cookies     = WasmUtils.convertJsonPlayCookies(response).getOrElse(Seq.empty)
                    val contentType = headers.getIgnoreCase("Content-Type").getOrElse("application/octet-stream")
                    Results
                      .Status(status)(body.getOrElse(ByteString.empty))
                      .withCookies(cookies: _*)
                      .withHeaders(headers.toSeq: _*)
                      .as(contentType)
                      .left
                  } else {
                    ctx.otoroshiResponse.copy(
                      status = (response \ "status").asOpt[Int].getOrElse(200),
                      headers =
                        (response \ "headers").asOpt[Map[String, String]].getOrElse(ctx.otoroshiResponse.headers),
                      body = body.map(_.chunks(16 * 1024)).getOrElse(ctx.otoroshiResponse.body),
                      cookies = WasmUtils.convertJsonCookies(response).getOrElse(ctx.otoroshiResponse.cookies),
                    ).right
                  }
                case Left(value) => Results.BadRequest(value).left
              }.andThen { case _ =>
                vm.release()
              }
          }
        })
    }
  }

  override def transformError(ctx: NgTransformerErrorContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[NgPluginHttpResponse] = {
    val pluginConfig = ctx
      .cachedConfig(internalName)(JsModulePluginConfig.format)
      .getOrElse(JsModulePluginConfig.default)
    val wasmConfig = pluginConfig.wasmConfig()
    getCode(pluginConfig).flatMap { code =>
      NgTransformerErrorContextHelper.wasmJson(ctx)
        .flatMap(input => {
          env.wasmIntegration.wasmVmFor(wasmConfig).flatMap {
            case None => pluginNotFound(ctx.request, ctx.route, ctx.attrs).map(r => NgPluginHttpResponseHelper.fromResult(r))
            case Some((vm, localConfig)) =>
              vm.call(
                WasmFunctionParameters.ExtismFuntionCall(
                  "cloud_apim_module_plugin_execute_on_error",
                  Json.obj(
                    "code" -> code,
                    "cause_id" -> ctx.maybeCauseId.map(JsString.apply).getOrElse(JsNull).asValue,
                    "call_attempts" -> ctx.callAttempts,
                    "message" -> ctx.message,
                    "otoroshi_response" -> ctx.otoroshiResponse.json,
                    "request" -> input.select("request").asOpt[JsValue].getOrElse(JsNull).asValue,
                    "snowflake" -> ctx.snowflake,
                    "body" -> input.select("request_body_bytes").asOpt[JsValue].getOrElse(JsNull).asValue,
                    "apikey" -> ctx.apikey.map(_.lightJson).getOrElse(JsNull).asValue,
                    "user" -> ctx.user.map(_.lightJson).getOrElse(JsNull).asValue,
                  ).stringify
                ),
                None
              ).map {
                case Right(res) =>
                  val response = Json.parse(res._1)
                  AttrsHelper.updateAttrs(ctx.attrs, response)
                  val body = BodyHelper.extractBodyFromOpt(response)
                  ctx.otoroshiResponse.copy(
                    status = (response \ "status").asOpt[Int].getOrElse(200),
                    headers =
                      (response \ "headers").asOpt[Map[String, String]].getOrElse(ctx.otoroshiResponse.headers),
                    body = body.map(_.chunks(16 * 1024)).getOrElse(ctx.otoroshiResponse.body),
                    cookies = WasmUtils.convertJsonCookies(response).getOrElse(ctx.otoroshiResponse.cookies),
                  )
                case Left(value) => NgPluginHttpResponseHelper.fromResult(Results.BadRequest(value))
              }.andThen { case _ =>
                vm.release()
              }
          }
        })
    }
  }
}
