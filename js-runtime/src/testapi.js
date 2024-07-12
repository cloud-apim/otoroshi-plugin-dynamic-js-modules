exports.on_backend_call = function(ctx) {
  const name = (ctx.request.query.name && ctx.request.query.name[0]) ? ctx.request.query.name[0] : 'World';
  host_api.console.log('on_backend_call', 'log', 'err');
  const item = host_api.storage.getItem('foo');
  if (!item) {
    host_api.console.error('item not found in storage !');
    host_api.storage.setItem('foo', "coucou !");
  }
  const items = host_api.storage.findItems('.*');
  return {
    status: 200,
    headers: {
      'Content-Type': 'application/json'
    },
    body_json: { hello: name, items, item }
  }
};