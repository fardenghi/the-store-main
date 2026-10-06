package com.amazon.sample.assistant.products.vector;

/** Un producto encontrado en Qdrant, con su similitud coseno. */
public record ScoredProduct(IndexedProduct product, float score) {
}
