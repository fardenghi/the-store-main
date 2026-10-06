# The Store

[![Build](https://github.com/jupmoreno/the-store/actions/workflows/main.yml/badge.svg)](https://github.com/jupmoreno/the-store/actions/workflows/main.yml)

**The Store** is a modern e-commerce platform built with microservices architecture.

Our platform provides a complete shopping experience with:
- **Beautiful storefront** with customizable themes and responsive design
- **Scalable microservices** built with multiple languages and frameworks
- **Real-time inventory management** and order processing

## 🏗️ Architecture

The Store is built with a microservices architecture that uses different technologies:

![Architecture](/docs/images/architecture.png)

| Service | Language | Description |
|---------|----------|-------------|
| [UI](./src/ui/) | Java (Spring Boot) | Modern web interface with themes and chat bot |
| [Catalog](./src/catalog/) | Go | Product catalog API with search and filtering |
| [Cart](./src/cart/) | Java (Spring Boot) | Shopping cart management with Redis/DynamoDB |
| [Orders](./src/orders/) | Java (Spring Boot) | Order processing and management |
| [Checkout](./src/checkout/) | Node.js (NestJS) | Checkout orchestration and payment processing |
| [Assistant](./src/assistant/) | Java (Spring Boot + Spring AI) | GenAI shopping assistant: the only service that talks to the LLM (NVIDIA) and to the embeddings model (Gemini) |
| Qdrant | Official image (`qdrant/qdrant`) | Vector store for the assistant, with a persistent volume. Internal only |

The updated architecture diagram and network table are in [docs/arquitectura.md](./docs/arquitectura.md).


## 🛠️ Development

### Prerequisites
- [Docker](https://docs.docker.com/get-docker/) running
- [Kind](https://kind.sigs.k8s.io/docs/user/quick-start/#installation) installed
- [Kubectl](https://kubernetes.io/docs/tasks/tools/install-kubectl/) installed

### Cluster Management

Use the `local.sh` script to manage your local Kubernetes cluster:

```bash
# Create a new cluster and deploy all services
./local.sh create-cluster

# Rebuild the entire cluster (delete and recreate)
./local.sh rebuild-cluster

# Delete the cluster
./local.sh delete-cluster

# Check cluster status
./local.sh status

# Build and load Docker images only
./local.sh reload-images

# Re-apply the assistant API keys and restart it
./local.sh update-secrets
```

After running `./local.sh create-cluster`, access The Store at: **http://localhost**.

### Assistant API keys

The assistant needs two API keys, which are never committed:

| Variable | Provider | Used for |
|----------|----------|----------|
| `NVIDIA_API_KEY` | [NVIDIA API Catalog](https://build.nvidia.com/) | Chat (`integrate.api.nvidia.com`) |
| `GOOGLE_API_KEY` | [Google AI Studio](https://aistudio.google.com/apikey) | Embeddings (`gemini-embedding-001`) |

Define them either as environment variables or in a `.env` file at the repo root (it is git-ignored, and it takes precedence over the environment):

```bash
# Option 1: environment variables
export NVIDIA_API_KEY=...
export GOOGLE_API_KEY=...

# Option 2: .env file at the repo root
cat > .env <<'ENV'
NVIDIA_API_KEY=...
GOOGLE_API_KEY=...
ENV
```

`./local.sh create-cluster` (and `rebuild-cluster`) stores them in the `assistant-api-keys` Secret. To change them on a running cluster without recreating it, edit `.env` (or the variables) and run:

```bash
./local.sh update-secrets
```

It re-applies the Secret and restarts the `assistant` deployment.

Without keys the cluster still comes up (`local.sh` prints a warning and uses a `not-configured` placeholder): every pod gets Ready and the rest of the store works, but the assistant does not answer.

### Testing

#### E2E Testing

Run end-to-end tests to validate the complete system:

```bash
# Run e2e tests on existing cluster
./local.sh e2e-test
```

**Note**: These tests are run automatically when creating or rebuilding the cluster. You can skip them using the `--skip-tests` parameter for faster setup:

```bash
# Create cluster without running tests (faster setup)
./local.sh create-cluster --skip-tests

# Rebuild cluster without running tests
./local.sh rebuild-cluster --skip-tests
```

#### Load Testing
Run load generator tests to validate system performance:

```bash
# Run load generator tests
./local.sh load-test
```

The load generator will run performance tests against your local cluster for 10 minutes (or until manually stopped) to validate system behavior under load.

## 🛋️ Product Catalog

The catalog (~80 home and furniture products) is generated from a curated list with `scripts/catalog-data/generate.py`. See [docs/how-to.md](./docs/how-to.md#catálogo-de-productos) for how to curate and regenerate it.

Product data and images are derived from the [Amazon Berkeley Objects (ABO)](https://amazon-berkeley-objects.s3.amazonaws.com/index.html) dataset by Amazon.com, licensed under [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/). Names, descriptions and prices were adapted, and images were resized ([attribution details](./docs/how-to.md#atribución)).

---

**The Store** - Built with ❤️ for modern e-commerce
