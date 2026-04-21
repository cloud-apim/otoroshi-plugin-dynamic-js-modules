package com.cloud.apim.otoroshi.plugins.jsdynmodules

import io.otoroshi.wasm4s.scaladsl.{WasmFunctionParameters, WasmSource, WasmSourceKind}
import otoroshi.env.Env
import otoroshi.next.workflow._
import otoroshi.utils.syntax.implicits._
import otoroshi.wasm.WasmConfig
import otoroshi_plugins.com.cloud.apim.plugins.jsdynmodules.JsModulePlugin
import play.api.libs.json._

import scala.concurrent.{ExecutionContext, Future}

object WorkflowFunctionsInitializer {
  def initDefaults(): Unit = {
    WorkflowFunction.registerFunction("extensions.com.cloud-apim.js-dynamic-modules-extension.call_function", new RunFunctionFunction())
  }
}

class RunFunctionFunction extends WorkflowFunction {

  override def documentationName: String                   = "extensions.com.cloud-apim.js-dynamic-modules-extension.call_function"
  override def documentationDisplayName: String            = "Call javascript function"
  override def documentationIcon: String                   = "fas fa-code"
  override def documentationDescription: String            = "This function calls a javascript function in QuickJS in a wasm vm"
  override def documentationInputSchema: Option[JsObject]  = Some(Json.obj(
    "type"       -> "object",
    "required"   -> Seq("code", "arguments"),
    "properties" -> Json.obj(
      "code" -> Json.obj("type" -> "string", "description" -> "The function code"),
      "arguments" -> Json.obj("type" -> "string", "description" -> "The function arguments")
    )
  ))
  override def documentationFormSchema: Option[JsObject]   = Some(Json.obj(
    "code" -> Json.obj(
      "type" -> "any",
      "description" -> "The function code",
      "props" -> Json.obj(
        "height" -> "300px",
        "language" -> "javascript",
        "config" -> Json.obj(
          "lineNumbers" -> true
        )
      )
    ),
    "arguments" -> Json.obj(
      "type"  -> "any",
      "props" -> Json.obj(
        "height" -> "300px",
        "language" -> "javascript",
        "config" -> Json.obj(
          "lineNumbers" -> true
        )
      ),
      "label" -> "Arguments"
    )
  ))
  override def documentationCategory: Option[String]       = Some("Cloud APIM - Js dynamic modules extension")
  override def documentationOutputSchema: Option[JsObject] = Some(Json.obj(
    "type"       -> "any",
    "description" -> "The function result"
  ))
  override def documentationExample: Option[JsObject]      = Some(Json.obj(
    "kind" -> "call",
    "function" -> "extensions.com.cloud-apim.llm-extension.tool_function_call",
    "args" -> Json.obj(
      "code" -> "exports.main = function(ctx) { ... }",
      "arguments" -> "{ ... }"
    )
  ))

  override def callWithRun(args: JsObject)(implicit env: Env, ec: ExecutionContext, wfr: WorkflowRun): Future[Either[WorkflowError, JsValue]] = {
    val code = args.select("code").asString
    val arguments = args.select("arguments").asOpt[JsObject].map(_.stringify)
      .orElse(args.select("arguments").asOpt[JsArray].map(_.stringify))
      .orElse(args.select("arguments").asOpt[JsNumber].map(_.stringify))
      .orElse(args.select("arguments").asOpt[JsBoolean].map(_.stringify))
      .orElse(args.select("arguments").asOpt[String])
      .getOrElse("")
    val pluginConfig = WasmConfig(source = WasmSource(WasmSourceKind.Local, JsModulePlugin.wasmPluginId, Json.obj()))
    env.wasmIntegration.wasmVmFor(pluginConfig).flatMap {
      case None => WorkflowError("Unable to find js runtime").leftf
      case Some((vm, localConfig)) =>
        vm.call(
          WasmFunctionParameters.ExtismFuntionCall(
            "cloud_apim_module_plugin_execute_user_function",
            Json.obj(
              "code" -> code,
              "env" -> Json.obj(),
              "module" -> "workflow_function.js",
              "externalApiUrl" -> JsNull,
              "externalApiHeaders" -> JsNull,
              "function_args" -> arguments,
              "memory" -> wfr.memory.json
            ).stringify
          ),
          None
        ).flatMap {
          case Right(res) =>
            val response = Json.parse(res._1)
            response.rightf
          case Left(value) => WorkflowError("Js exec error", Json.obj("js_error" -> value).some).leftf
        }.andThen { case _ =>
          vm.release()
        }
    }.recover {
      case t: Throwable =>
        WorkflowError("Js recover error", Json.obj("js_error" -> t.getMessage).some).left
    }
  }
}
