# 🌩️ Cloudflare Exporter

**Cloudflare Exporter** is a lightweight Prometheus exporter written in Scala. It collects metrics from the [Cloudflare GraphQL API](https://developers.cloudflare.com/api/graphql/) and exposes them via `/metrics` endpoint in Prometheus format.

## 🚀 Features

- Retrieves top IPs triggering Cloudflare firewall rules
- Aggregates data across multiple zones and accounts
- Exposes Prometheus-compatible metrics
- Supports zone filtering via environment config
- Lightweight and containerized for easy deployment

## 📦 Docker

You can run the exporter using Docker:

```bash
docker run -p 8080:8080 \
  -e CLOUDFLARE_API_TOKEN=your_token \
  -e CLOUDFLARE_AUTH_EMAIL=your_email \
  ghcr.io/your-org/cloudflare-exporter:latest
```

## 📈 Metrics

Metrics are exposed at:

```
GET /metrics
```

Example metric:

```
cloudflare_top_ip_request_count{zone="example.com", action="block", source="firewallCustom", rule="abc123"} 456
```

## ⚙️ Configuration

| Environment Variable                  | Description                                      |
|--------------------------------------|--------------------------------------------------|
| `CLOUDFLARE_API_TOKEN`               | Cloudflare API token (required)                  |
| `CLOUDFLARE_AUTH_EMAIL`              | Email associated with Cloudflare API token       |
| `CLOUDFLARE_ACCOUNT_ID`              | Comma-separated list of allowed account IDs      |
| `CLOUDFLARE_DISABLES_ZONES_FIREWALL` | Comma-separated list of zone IDs to exclude      |

## 🛠️ Development

Build and run locally:

```bash
sbt run
```

Or build a fat JAR:

```bash
sbt assembly
```

Then run:

```bash
java -jar target/scala-2.13/cloudflare-exporter-assembly-*.jar
```

## 🤖 GitHub Actions

A GitHub Actions workflow builds and publishes the Docker image to GitHub Container Registry (GHCR).

## 🔒 Permissions

The following Cloudflare API permissions are required:

- `#zone:read`
- `#logs:read`
- `#analytics:read`
- `#waf:read`

---

## 📜 License

MIT License – © 2025 YourName or YourOrg
