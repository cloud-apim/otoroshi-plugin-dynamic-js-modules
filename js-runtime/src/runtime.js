function eval_externalApiCode(module, externalApiUrl, externalApiHeaders) {
  if (externalApiUrl) {
    return `
      const host_api = (function() {
      
        const externalApiHeaders = ${JSON.stringify(externalApiHeaders)};
      
        function raw_log(level, ...messages) {
          Http.request({
            url: '${externalApiUrl}/apis/v1/logger/_rpc',
            method: 'POST',
            headers: {
              'Accept': 'application/json',
              'Content-Type': 'application/json',
              'X-Wasm-Module': '${module}',
              ...externalApiHeaders
            },
          }, JSON.stringify({
            module: '${module}',
            level,
            messages,
          }));
        }
        
        function raw_storage(operation, key, value, opts) {
          let finalValue = value || null;
          if (finalValue && !(typeof finalValue === 'string' || finalValue instanceof String)) {
            finalValue = JSON.stringify(finalValue);
          }
          const response = Http.request({
            url: '${externalApiUrl}/apis/v1/storage/_rpc',
            method: 'POST',
            headers: {
              'Accept': 'application/json',
              'Content-Type': 'application/json',
              'X-Wasm-Module': '${module}',
              ...externalApiHeaders
            },
          }, JSON.stringify({
            operation,
            key,
            value: finalValue,
            opts: opts || {}
          }));
          let responseBody = response.body;
          if (typeof responseBody === 'string' || responseBody instanceof String) {
            responseBody = JSON.parse(responseBody);
          }
          if (response.status === 200 || response.status === 201 || response.status === 204) {
            return responseBody ? responseBody.value || null : null;
          } else {
            throw new Error(responseBody.error, { cause: responseBody.error_description })
          }
        }
        
        console.old_log = console.log;
        console.log = (...args) => raw_log('info', ...args);
        
        //console.old_error = console.error;
        //console.error = (...args) => raw_log('error', ...args);
        //console.old_info = console.info;
        //console.info = (...args) => raw_log('info', ...args);
      
        return {
          console: {
            raw: (level, ...args) => raw_log(level, ...args),
            log: (...args) => raw_log('info', ...args),
            info: (...args) => raw_log('info', ...args),
            warn: (...args) => raw_log('warn', ...args),
            error: (...args) => raw_log('error', ...args),
            debug: (...args) => raw_log('debug', ...args),
          },
          storage: {
            allItems: () => raw_storage('find', '.*', null),
            allKeys: () => raw_storage('find', '.*', null).map(item => item.key),
            findItems: (pattern) => raw_storage('find', pattern, null),
            getItem: (key) => raw_storage('get', key, null),
            removeItem: (key) => raw_storage('remove', key, null),
            setItem: (key, value, opts) => raw_storage('set', key, value, opts),
            clear: () => raw_storage('clear', null, null),
          }
        };
      })();
    `;
  } else {
    return '';
  }
}

function eval_source(src, module, env, externalApiUrl, externalApiHeaders) {
  const externalApiCode = eval_externalApiCode(module, externalApiUrl, externalApiHeaders)
  // TODO: remove all global objects from extism
  const code = `(function() {
    var exports = {};
    
    var process = {
      env: ${JSON.stringify(env)}
    };

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
    ${externalApiCode}
    ${src}
    return exports;
  })();`
  return eval(code);
}

function cloud_apim_module_plugin_execute(phase, idx) {
  try {
    const inputStr = Host.inputString();
    const inputJson = JSON.parse(inputStr);
    const code = inputJson.code;
    const module = inputJson.module;
    const cleanedInput = { ...inputJson };
    delete cleanedInput.code;
    delete cleanedInput.module;
    delete cleanedInput.externalApiUrl;
    delete cleanedInput.externalApiHeaders;
    const exps = eval_source(code, module, inputJson.env || {}, inputJson.externalApiUrl, inputJson.externalApiHeaders || {});
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