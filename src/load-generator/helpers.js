let productIds = [
  "3600929b-2826-5a98-908f-82a1d50bcf2b",
  "bff46bca-ec50-582f-9cde-6d843c1de176",
  "2c5a89d4-9ce2-5d70-9058-aaef9514d5e7",
  "fa43ff4b-72fe-555e-b7d1-01eff88ffa9f",
  "e224d232-0d4c-593f-9f20-a836b5061b3f",
  "9b406825-c8d5-5d9b-8d87-08118b7e9aaa",
  "0043cb7c-2143-5c37-8898-bf8881c86ac8",
  "38907885-34e5-51a6-99a6-1cf4cc83c54d",
  "a7585cf5-96ac-5e8c-a95b-991c61ba3e5c",
];

function getAllProducts(context, ee, next) {
  context.vars.allProducts = productIds;

  next();
}

function setRandomProductId(req, context, ee, next) {
  const index = Math.floor(Math.random() * productIds.length);

  req.form.productId = productIds[index];

  next();
}

module.exports = {
  setRandomProductId,
  getAllProducts,
};
