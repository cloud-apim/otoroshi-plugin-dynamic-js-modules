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