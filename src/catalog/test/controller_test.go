package test

import (
	"encoding/json"
	"fmt"
	"net/http"
	"sort"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"github.com/aws-containers/retail-store-sample-app/catalog/model"
	"github.com/aws-containers/retail-store-sample-app/catalog/repository"
)

const (
	// "Eva Tufted Velvet Sofa" (ABO B075X4QMW7)
	evaSofaID = "3600929b-2826-5a98-908f-82a1d50bcf2b"
	// "Temporal Tickstopper", del catálogo spy anterior
	oldSpyProductID = "cc789f85-1476-452a-8100-9e74502198e0"
)

func getProducts(t *testing.T, url string) []model.Product {
	t.Helper()

	writer := makeRequest("GET", url, nil)
	require.Equal(t, http.StatusOK, writer.Code)

	var response []model.Product
	require.NoError(t, json.Unmarshal(writer.Body.Bytes(), &response))

	return response
}

func getSize(t *testing.T, url string) int {
	t.Helper()

	writer := makeRequest("GET", url, nil)
	require.Equal(t, http.StatusOK, writer.Code)

	var response model.CatalogSizeResponse
	require.NoError(t, json.Unmarshal(writer.Body.Bytes(), &response))

	return response.Size
}

// expectedIDs devuelve los ids de los productos embebidos que tienen alguno de los tags.
func expectedIDs(t *testing.T, tags ...string) []string {
	t.Helper()

	products, err := repository.LoadProductData()
	require.NoError(t, err)

	ids := []string{}
	for _, p := range products {
		for _, tag := range p.Tags {
			if contains(tags, tag) {
				ids = append(ids, p.ID)
				break
			}
		}
	}
	sort.Strings(ids)

	return ids
}

func productIDs(products []model.Product) []string {
	ids := []string{}
	for _, p := range products {
		ids = append(ids, p.ID)
	}
	sort.Strings(ids)

	return ids
}

func contains(values []string, value string) bool {
	for _, v := range values {
		if v == value {
			return true
		}
	}
	return false
}

func TestCatalogList(t *testing.T) {
	response := getProducts(t, "/catalog/products")

	assert.Equal(t, 10, len(response))
}

func TestCatalogSize(t *testing.T) {
	products, err := repository.LoadProductData()
	require.NoError(t, err)

	assert.Equal(t, len(products), getSize(t, "/catalog/size"))
}

func TestCatalogTags(t *testing.T) {
	writer := makeRequest("GET", "/catalog/tags", nil)
	assert.Equal(t, http.StatusOK, writer.Code)

	var response []model.Tag
	require.NoError(t, json.Unmarshal(writer.Body.Bytes(), &response))

	assert.Equal(t, 18, len(response))
}

func TestCatalogProduct(t *testing.T) {
	writer := makeRequest("GET", "/catalog/products/"+evaSofaID, nil)

	assert.Equal(t, http.StatusOK, writer.Code)

	var response model.Product
	require.NoError(t, json.Unmarshal(writer.Body.Bytes(), &response))

	assert.Equal(t, "Eva Tufted Velvet Sofa", response.Name)
	assert.GreaterOrEqual(t, len(response.Tags), 2)
}

func TestCatalogProductMissing(t *testing.T) {
	writer := makeRequest("GET", "/catalog/products/missing", nil)

	assert.Equal(t, http.StatusNotFound, writer.Code)
}

func TestCatalogOldSpyProductRemoved(t *testing.T) {
	writer := makeRequest("GET", "/catalog/products/"+oldSpyProductID, nil)

	assert.Equal(t, http.StatusNotFound, writer.Code)
}

func TestCatalogTagsUnion(t *testing.T) {
	response := getProducts(t, "/catalog/products?tags=velvet,leather&size=100")

	assert.Equal(t, expectedIDs(t, "velvet", "leather"), productIDs(response))
}

func TestCatalogTagsNoDuplicates(t *testing.T) {
	response := getProducts(t, "/catalog/products?tags=seating,living-room&size=100")

	ids := productIDs(response)
	seen := map[string]bool{}
	for _, id := range ids {
		assert.False(t, seen[id], "id repetido: %s", id)
		seen[id] = true
	}

	assert.Equal(t, expectedIDs(t, "seating", "living-room"), ids)
	assert.Equal(t, len(response), getSize(t, "/catalog/size?tags=seating,living-room"))
}

func TestCatalogTagsPagination(t *testing.T) {
	size := getSize(t, "/catalog/size?tags=seating,living-room")

	seen := map[string]bool{}
	total := 0
	for page := 1; ; page++ {
		response := getProducts(t, fmt.Sprintf("/catalog/products?tags=seating,living-room&size=6&page=%d", page))
		if len(response) == 0 {
			break
		}
		for _, p := range response {
			assert.False(t, seen[p.ID], "producto repetido entre páginas: %s", p.ID)
			seen[p.ID] = true
		}
		total += len(response)
	}

	assert.Equal(t, size, total)
	assert.Equal(t, size, len(seen))
}

func TestCatalogCheaperAlternatives(t *testing.T) {
	response := getProducts(t, "/catalog/products?tags=seating&order=price_asc&size=100")

	prices := map[int]bool{}
	for i, p := range response {
		prices[p.Price] = true
		if i > 0 {
			assert.LessOrEqual(t, response[i-1].Price, p.Price)
		}
	}

	assert.GreaterOrEqual(t, len(prices), 3)
}
