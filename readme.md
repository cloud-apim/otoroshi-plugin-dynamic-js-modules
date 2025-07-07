# Cloud APIM dynamic js modules 

This project provides a new kind of [Otoroshi](https://github.com/MAIF/otoroshi) plugin that let you run JS embbeded in a WASM runtime without the compilation phase that is mandatory with the classic WASM plugin

Here with the `Js Module plugin` you just have to specify the name of the module and where to get it and you're fine

## Plugin configuration

you can configure the plugins as the following:

```js
{
  "runtime_ref": "", // the wasm plugin ref for the runtime, if none provided, the plugin will create its own and you will be able to customize it later
  "module": "https://github.com/cloud-apim/otoroshi-plugin-dynamic-js-modules/raw/main/js-runtime/src/hello.js",
  "module_path": "/hello.js", // path is only here for readability of error messages
  "headers": {} // if the module is available through http, you can provide headers
}
```

## Module content

the module that will be ran is supposed to be like:

```js
exports.on_validate = function(ctx) { ... }
exports.on_request = function(ctx) { ... }
exports.on_backend_call = function(ctx) { ... }
exports.on_response = function(ctx) { ... }
exports.on_error = function(ctx) { ... }
```

only define the functions you want to use. If a method is not defined, the plugin will not try to call it.

here is an example of a working module that return a hello message along with a hello header:

```js
exports.on_backend_call = function(ctx) {
  const name = (ctx.request.query.name && ctx.request.query.name[0]) ? ctx.request.query.name[0] : 'World';
  return {
    status: 200,
    headers: {
      'Content-Type': 'application/json'
    },
    body_json: { hello: name }
  }
};

exports.on_response = function(ctx) {
  const name = (ctx.request.query.name && ctx.request.query.name[0]) ? ctx.request.query.name[0] : 'World';
  return {
    ...ctx.otoroshi_response,
    headers: {
      ...ctx.otoroshi_response.headers,
      'hello': name,
    }
  }
}
```

the context passed to the functions is structured like:

```js
{
  "snowflake": "xxxx", // request unique id
  "request": { // original request unchanged by otoroshi
    "method": "GET",
    "path": "/foo",
    "headers": {
      "user-agent":"foo"
    }
  },
  "raw_request": {  // only defined in the on_request phase
    "method": "GET",
    "path": "/foo",
    "headers": {
      "user-agent":"foo"
    }
  },
  "otoroshi_request": { // only defined in the on_request phase
    "method": "GET",
    "path": "/foo",
    "headers": {
      "user-agent":"foo"
    }
  }, 
  "body": [12, 23, 34], // request body as a byte array, not defined in the on_validate phase
  "apikey": { // the request apikey if provided
    "clientId": "foo",
    "clientName": "foo",
    "metadata": [],
    "tags": [],
  },
  "user": { // the request user if provided
    "name": "foo",
    "email": "foo@foo.com",
    "profile": {}, // idp dependant user profile
    "metadata":[],
    "tags": [],
  }
  "raw_response": { // only defined in the on_response and on_error phase
    "status": 200,
    "headers": {
      "content-type": "text/html"
    },
    "body": [12, 23, 34]
  },
  "otoroshi_response": { // only defined in the on_response and on_error phase
    "status": 200,
    "headers": {
      "content-type": "text/html"
    },
    "body": [12, 23, 34]
  },
  "cause_id": "xxx", // only defined in the on_error phase
  "call_attempts": 3, // only defined in the on_error phase
  "message": "error", // only defined in the on_error phase
}
```

the return structures are the following:

for the `on_validate` phase:

```js 
return {
  result: true, // if result is false, then error is returned to client
  error: {
    "status": 200,
    "headers": {
      "content-type": "application/json"
    },
    "body_json": { // can be also body_bytes, body_base64, body_str
      "error": "..."
    }
  }
}
```

for the `on_request` phase:

```js 
return {
  "error": false, // if error is true, then an error is returned to client with the response specified 
  "status": 200,
  "headers": {
    "content-type": "application/json"
  },
  "body_json": { // can be also body_bytes, body_base64, body_str
    "error": "..."
  },
  "otoroshi_request": {...} // the otoroshi_request from the context with modifications if needed and error === false
}
```

for the `on_backend_call` phase:

```js 
return {
  "delegates_call": false, // if true, response is ignored and otoroshi will forward request to the actual backend
  "status": 200,
  "headers": {
    "content-type": "application/json"
  },
  "body_json": { // can be also body_bytes, body_base64, body_str
    "error": "..."
  }
}
```

for the `on_response` phase:

```js 
return {
  "error": false, // if error is true, then an error is returned to client with the response specified 
  "status": 200,
  "headers": {
    "content-type": "application/json"
  },
  "body_json": { // can be also body_bytes, body_base64, body_str
    "error": "..."
  },
  "otoroshi_response": {...} // the otoroshi_response from the context with modifications if needed and error === false
}
```

for the `on_error` phase:

```js 
return {
  "status": 200,
  "headers": {
    "content-type": "application/json"
  },
  "body_json": { // can be also body_bytes, body_base64, body_str
    "error": "..."
  }
}
```

## Known limitations

promise are not supported right now. If you run code that used promises, then your module won't return anything. We are working on it.

If you want to call something using http, we provide a limited implementation of `fetch` that uses some kind of fake synchronous promises
