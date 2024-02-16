cd ./js-runtime
npm install
npm run build
cp ./dist/otoroshi-plugin-dynamic-js-modules-runtime.wasm ../src/main/resources/wasm/otoroshi-plugin-dynamic-js-modules-runtime.wasm