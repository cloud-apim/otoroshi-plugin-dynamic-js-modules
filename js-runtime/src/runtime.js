function eval_source(src) {
  // TODO: remove all global objects from extism
  const code = `(function() {
    var exports = {};

    function FakeResolvedPromise(result) {
      return {
        value: result,
        then: (f) => {
          const r = f(result);
          if (r && r.then) {
            return r;
          } else {
            return FakeResolvedPromise(r);
          }
        },
        catch: (f) => FakeResolvedPromise(result),
      }
    }

    function FakeRejectedPromise(result) {
      return {
        error: result,
        then: (a, f) => {
          if (f) {
            const r = f(result);
            if (r && r.then) {
              return r;
            } else {
              return FakeResolvedPromise(r);
            }
          } else {
            return FakeRejectedPromise(result);
          }
        },
        catch: (f) => {
          const r = f(result);
          if (r.then) {
            return r;
          } else {
            return FakeResolvedPromise(r);
          }
        }
      }
    }

    const FPromise = {
      resolve: (value) => new FakeResolvedPromise(value),
      reject: (value) => new FakeRejectedPromise(value),
    }

    function fetch(url, _opts) {

      function makeResponse(response) {
        return {
          rawResponse: response,
          status: response.status,
          statusText: 'none',
          headers: response.headers,
          ok: response.status > 199 && response.status < 300,
          redirected: response.status > 299 && response.status < 400,
          clone: () => {
            return makeResponse(response);
          },
          text: () => {
            return FPromise.resolve(response.body);
          },
          json: () => {
            return FPromise.resolve(JSON.parse(response.body));
          },
          blob: () => {
            return FPromise.reject(new Error('unsupported method blob'));
          },
          formData: () => {
            return FPromise.reject(new Error('unsupported method formData'));
          },
          arrayBuffer: () => {
            return FPromise.reject(new Error('unsupported method arrayBuffer'));
          },
          error: () => {
            return FPromise.reject(new Error('unsupported method error'));
          },
          redirected: () => {
            return FPromise.reject(new Error('unsupported method redirected'));
          }
        };
      }

      const opts = _opts || {};

      try {
        let response = {
          status: 0,
          headers: {},
          body: null,
        };
        if (opts.body) {
          const r = Http.request({
            url: url,
            method: opts.method || 'GET',
            headers: opts.headers || {},
          }, opts.body);
          response.status = r.status;
          response.body = r.body;
        } else {
          const r = Http.request({
            url: url,
            method: opts.method || 'GET',
            headers: opts.headers || {},
          });
          response.status = r.status;
          response.body = r.body;
        }
        return FPromise.resolve(makeResponse(response));
      } catch(e) {
        return FPromise.reject(e);
      }
    }
    ${src}
    return exports;
  })();`
  return eval(code);
}

function cloud_apim_module_plugin_execute(phase) {
  try {
    const inputStr = Host.inputString();
    const inputJson = JSON.parse(inputStr);
    const code = inputJson.code;
    const cleanedInput = inputJson;
    const exps = eval_source(code);
    if (exps[phase]) {
      const result = exps[phase](cleanedInput);
      if (result) {
        if (result.then && result.value) {
          Host.outputString(JSON.stringify(result.value));
        } else {
          Host.outputString(JSON.stringify(result));
        }
      } else {
        Host.outputString(JSON.stringify({
          error: true,
          status: 500,
          headers: {
            'Content-Type': 'application/json'
          },
          body_str: 'no result'
        }));
      }
    } else {
      if (phase === 'on_validate') {
        Host.outputString(JSON.stringify({ result: true }));
      } else if (phase === 'on_backend_call') {
        Host.outputString(JSON.stringify({ delegates_call: true }));
      } else if (phase === 'on_request') {
        Host.outputString(JSON.stringify(inputJson.otoroshi_request));
      } else if (phase === 'on_response') {
        Host.outputString(JSON.stringify(inputJson.otoroshi_response));
      } else if (phase === 'on_error') {
        Host.outputString(JSON.stringify({ ...inputJson.otoroshi_response }));
      }
    }
  } catch(e) {
    Host.outputString(JSON.stringify({
      error: true,
      status: 500,
      headers: {
        'Content-Type': 'application/json'
      },
      body_json: {
        error:  e.message,
      }
    }));
  }
}

function cloud_apim_module_plugin_execute_on_validate() {
  cloud_apim_module_plugin_execute('on_validate');
}
function cloud_apim_module_plugin_execute_on_request() {
  cloud_apim_module_plugin_execute('on_request');
}
function cloud_apim_module_plugin_execute_on_backend_call() {
  cloud_apim_module_plugin_execute('on_backend_call');
}
function cloud_apim_module_plugin_execute_on_response() {
  cloud_apim_module_plugin_execute('on_response');
}
function cloud_apim_module_plugin_execute_on_error() {
  cloud_apim_module_plugin_execute('on_error');
}

module.exports = { 
  cloud_apim_module_plugin_execute_on_validate,
  cloud_apim_module_plugin_execute_on_request, 
  cloud_apim_module_plugin_execute_on_backend_call,
  cloud_apim_module_plugin_execute_on_response, 
  cloud_apim_module_plugin_execute_on_error, 
};