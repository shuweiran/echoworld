# EchoWorld production deployment — Tencent Cloud Lighthouse

This deployment targets a small single-node production server and keeps the Java application off the public network. Caddy is the only Internet-facing service and terminates HTTPS automatically.

## Recommended server

As of 2026-09-11, the Tencent Cloud Lighthouse China Hong Kong entry Linux plan is a good fit:

- 2 vCPU
- 2 GB RAM
- 40 GB SSD
- 20 Mbps peak bandwidth
- 512 GB/month traffic
- Linux

Use Ubuntu 24.04 LTS (or another current Ubuntu LTS). Open inbound TCP ports `22`, `80`, and `443`; UDP `443` is optional but enables HTTP/3. Do **not** open port `8000`.

## Domain and DNS

Use a normal `.com` domain from a registrar with predictable renewal pricing. Point an `A` record for the selected hostname to the Lighthouse public IPv4 address.

If Cloudflare DNS is used, start with the record in **DNS only** mode until Caddy has obtained the certificate. The Cloudflare proxy can be evaluated later.

Example:

```text
SITE_DOMAIN=app.example.com
A app.example.com -> <LIGHTHOUSE_PUBLIC_IP>
```

## 1. Prepare the 2 GB server

A 2 GB swap file prevents the one-time Maven + npm Docker build from failing under memory pressure. Runtime memory is separately capped in `docker-compose.prod.yml`.

```bash
sudo fallocate -l 2G /swapfile
sudo chmod 600 /swapfile
sudo mkswap /swapfile
sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab

curl -fsSL https://get.docker.com | sudo sh
sudo usermod -aG docker "$USER"
```

Log out and back in once after adding the Docker group.

## 2. Clone and configure

```bash
git clone https://github.com/shuweiran/echoworld.git
cd echoworld
cp .env.prod.example .env
nano .env
```

Set at least:

```dotenv
SITE_DOMAIN=app.example.com
ROLEPLAY_LLM_API_KEY=<real-api-key>
```

Never commit `.env`.

## 3. Build and start

```bash
docker compose -p echoworld -f docker-compose.prod.yml build
docker compose -p echoworld -f docker-compose.prod.yml up -d
```

Check status and logs:

```bash
docker compose -p echoworld -f docker-compose.prod.yml ps
docker compose -p echoworld -f docker-compose.prod.yml logs --tail=200 roleplay
docker compose -p echoworld -f docker-compose.prod.yml logs --tail=100 caddy
```

Then visit:

```text
https://<SITE_DOMAIN>
```

Caddy obtains and renews the TLS certificate automatically.

## 4. Updating EchoWorld

```bash
cd ~/echoworld
git pull --ff-only
docker compose -p echoworld -f docker-compose.prod.yml build
docker compose -p echoworld -f docker-compose.prod.yml up -d
```

Old unused images can occasionally be removed with:

```bash
docker image prune -f
```

## 5. Persistent data and backup

H2/runtime data lives in the Docker volume `echoworld_roleplay-data`, mounted at `/app/data`. Rebuilding or replacing the application container does not delete it.

Create a backup:

```bash
mkdir -p ~/echoworld-backups
docker run --rm \
  -v echoworld_roleplay-data:/data:ro \
  -v "$HOME/echoworld-backups:/backup" \
  alpine sh -c 'tar czf /backup/roleplay-data-$(date +%F-%H%M%S).tar.gz -C /data .'
```

## Network model

```text
Internet
   |
80/443
   |
 Caddy  ---- private Docker network ---->  EchoWorld :8000
                                               |
                                         /app/data volume
```

`application.yml` intentionally binds to localhost for desktop/local use. The production compose file overrides `SERVER_ADDRESS=0.0.0.0` only inside the private Docker network, while port `8000` is not published to the host. This keeps the application behind the controlled reverse proxy.
