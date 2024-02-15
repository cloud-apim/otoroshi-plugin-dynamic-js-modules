declare module 'main' {
  export function cloud_apim_module_plugin_execute_on_validate(): I32;
  export function cloud_apim_module_plugin_execute_on_request(): I32;
  export function cloud_apim_module_plugin_execute_on_backend_call(): I32;
  export function cloud_apim_module_plugin_execute_on_response(): I32;
  export function cloud_apim_module_plugin_execute_on_error(): I32;
}