package repository

import (
	"regexp"
	"strings"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

// Invariantes del catálogo (openspec: product-catalog) verificadas sobre el JSON embebido.

var (
	typeTags  = []string{"seating", "tables", "storage", "lighting", "rugs", "decor", "beds"}
	roomTags  = []string{"living-room", "bedroom", "office", "dining"}
	styleTags = []string{"mid-century", "modern", "rustic", "velvet", "leather", "wood", "metal"}

	uuidV5Pattern         = regexp.MustCompile(`^[0-9a-f]{8}-[0-9a-f]{4}-5[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$`)
	kebabCasePattern      = regexp.MustCompile(`^[a-z]+(-[a-z]+)*$`)
	forbiddenWordsPattern = regexp.MustCompile(`(?i)warranty|return`)
)

func loadCatalog(t *testing.T) ([]ProductData, []ProductTagData) {
	t.Helper()

	products, err := LoadProductData()
	require.NoError(t, err)

	tags, err := LoadProductTagData()
	require.NoError(t, err)

	return products, tags
}

func contains(values []string, value string) bool {
	for _, v := range values {
		if v == value {
			return true
		}
	}
	return false
}

func TestCatalogSize(t *testing.T) {
	products, _ := loadCatalog(t)

	assert.GreaterOrEqual(t, len(products), 70)
	assert.LessOrEqual(t, len(products), 90)
}

func TestTagTaxonomy(t *testing.T) {
	_, tags := loadCatalog(t)

	expected := append(append(append([]string{}, typeTags...), roomTags...), styleTags...)
	names := []string{}
	for _, tag := range tags {
		names = append(names, tag.Name)
		assert.Regexp(t, kebabCasePattern, tag.Name)
		assert.NotEmpty(t, strings.TrimSpace(tag.DisplayName), "tag %s sin displayName", tag.Name)
	}

	assert.ElementsMatch(t, expected, names)
}

func TestProductTags(t *testing.T) {
	products, tags := loadCatalog(t)

	tagNames := []string{}
	for _, tag := range tags {
		tagNames = append(tagNames, tag.Name)
	}

	for _, p := range products {
		typeCount := 0
		otherCount := 0
		for _, tag := range p.Tags {
			assert.True(t, contains(tagNames, tag), "producto %s: tag inexistente %s", p.Name, tag)
			if contains(typeTags, tag) {
				typeCount++
			} else {
				otherCount++
			}
		}
		assert.Equal(t, 1, typeCount, "producto %s: tiene que tener exactamente un tag de tipo", p.Name)
		assert.GreaterOrEqual(t, otherCount, 1, "producto %s: necesita un tag de ambiente o estilo/material", p.Name)
	}
}

func TestProductsPerTag(t *testing.T) {
	products, tags := loadCatalog(t)

	counts := map[string]int{}
	for _, p := range products {
		for _, tag := range p.Tags {
			counts[tag]++
		}
	}

	for _, tag := range tags {
		minimum := 3
		if contains(typeTags, tag.Name) {
			minimum = 5
		}
		assert.GreaterOrEqual(t, counts[tag.Name], minimum, "tag %s", tag.Name)
	}
}

func TestProductNamesAndDescriptions(t *testing.T) {
	products, _ := loadCatalog(t)

	names := map[string]bool{}
	for _, p := range products {
		assert.NotEmpty(t, p.Name)
		assert.LessOrEqual(t, len(p.Name), 60, "nombre demasiado largo: %s", p.Name)
		assert.False(t, names[strings.ToLower(p.Name)], "nombre repetido: %s", p.Name)
		names[strings.ToLower(p.Name)] = true

		assert.GreaterOrEqual(t, len(p.Description), 150, "descripción corta: %s", p.Name)
		assert.NotRegexp(t, forbiddenWordsPattern, p.Description, "descripción con garantía o devoluciones: %s", p.Name)
	}
}

func TestProductPrices(t *testing.T) {
	products, _ := loadCatalog(t)

	distinctPrices := map[string]map[int]bool{}
	for _, p := range products {
		assert.Greater(t, p.Price, 0, "precio inválido: %s", p.Name)
		for _, tag := range p.Tags {
			if contains(typeTags, tag) {
				if distinctPrices[tag] == nil {
					distinctPrices[tag] = map[int]bool{}
				}
				distinctPrices[tag][p.Price] = true
			}
		}
	}

	for _, tag := range typeTags {
		assert.GreaterOrEqual(t, len(distinctPrices[tag]), 3, "tag %s", tag)
	}
}

func TestProductIDs(t *testing.T) {
	products, _ := loadCatalog(t)

	ids := map[string]bool{}
	for _, p := range products {
		assert.Regexp(t, uuidV5Pattern, p.ID)
		assert.False(t, ids[p.ID], "id repetido: %s", p.ID)
		ids[p.ID] = true
	}
}
