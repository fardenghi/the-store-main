package com.amazon.sample.assistant.products.vector;

/** Un punto a insertar: el vector del producto y su payload. */
public record ProductPoint(IndexedProduct product, float[] vector) {
}
