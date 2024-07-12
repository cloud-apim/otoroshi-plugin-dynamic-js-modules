const express = require('express')
const bodyParser = require('body-parser')

const app = express()

app.use(bodyParser.json({}));

const port = 3004

let storage = {};

app.post('/apis/v1/logger/_rpc', (req, res) => {
  console.log('logger', req.body)
  res.status(204).send('')
})

app.post('/apis/v1/storage/_rpc', (req, res) => {
  console.log('storage', req.body)
  const operation = req.body.operation;
  const key = req.body.key;
  const value = req.body.value;
  if (operation === 'get') {
    const opt = storage[key];
    res.status(200).send({ value: opt, key });
  } else if (operation === 'remove') {
    delete storage[key];
    res.status(204).send('')
  } else if (operation === 'set') {
    const previous = storage[key] ? { key, value: storage[key] } :  null;
    storage[key] = value;
    res.status(200).send({ key, value, previous });
  } else if (operation === 'find') {
    const arr = Object.entries(storage).map(([k, value]) => {
      if (!!k.match(key)) {
        return { key: k, value };
      } else {
        return null;
      }
    }).filter(i => !!i);
    res.status(200).send({ value: arr });
  } else if (operation === 'clear') {
    storage = {};
    res.status(204).send('')
  } else {
    res.status(404).send({})
  }
})

app.listen(port, () => {
  console.log(`server listening on port ${port}`)
})