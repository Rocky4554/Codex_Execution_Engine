# nginx Setup — Codex Execution Engine EC2

Everything configured on `ubuntu@3.110.161.110` during the nginx learning session.

---

## Architecture

```
Internet
    │
    ├── port 22   → SSH (always open)
    ├── port 80   → nginx → code_execution → Java Docker agent :8081
    ├── port 8082 → nginx → mysite → /var/www/mysite (static HTML)
    └── port 8081 → BLOCKED by ufw (internal only — nginx still proxies to it)
```

---

## Folder Structure

| Path | Purpose |
|------|---------|
| `/etc/nginx/nginx.conf` | Main config — global settings (gzip, etc.) |
| `/etc/nginx/sites-available/` | Write configs here — nginx ignores this folder directly |
| `/etc/nginx/sites-enabled/` | Symlinks to sites-available — nginx only reads this |
| `/var/www/mysite/` | Static site files |
| `/var/log/nginx/access.log` | Every request logged here |
| `/var/log/nginx/error.log` | Errors logged here — first stop for debugging |

**Rule:** write config in `sites-available`, symlink to `sites-enabled` to activate.

```bash
sudo ln -s /etc/nginx/sites-available/myconfig /etc/nginx/sites-enabled/
sudo nginx -t && sudo systemctl reload nginx
```

---

## Config 1 — code_execution (Reverse Proxy)

**File:** `/etc/nginx/sites-available/code_execution`

```nginx
server {
    listen 80;
    server_name _;

    location / {
        proxy_pass http://127.0.0.1:8081;
        proxy_set_header Host              $host;
        proxy_set_header X-Real-IP         $remote_addr;
        proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

- Listens on port 80 (default HTTP)
- Forwards all requests to the Java Docker agent on `localhost:8081`
- `proxy_set_header` passes real client IP/host to the backend (without this, backend sees nginx's IP)

---

## Config 2 — mysite (Static HTML)

**File:** `/etc/nginx/sites-available/mysite`

```nginx
server {
    listen 8082;
    server_name _;

    root /var/www/mysite;
    index index.html;

    location / {
        try_files $uri $uri/ =404;
    }

    # cache static assets for 30 days
    location ~* \.(css|js|jpg|jpeg|png|gif|ico|svg|woff|woff2)$ {
        expires 30d;
        add_header Cache-Control "public, no-transform";
    }
}
```

- Listens on port 8082
- Serves static files from `/var/www/mysite`
- `try_files` — tries the exact file, then directory, then returns 404
- `~*` in location = case-insensitive regex match on file extensions
- Static assets cached in browser for 30 days

---

## Gzip Compression

**File:** `/etc/nginx/nginx.conf` — inside the `http { }` block

```nginx
gzip on;
gzip_types text/plain text/css application/json application/javascript text/xml text/html;
gzip_min_length 1024;
gzip_comp_level 6;
```

| Setting | Value | Why |
|---------|-------|-----|
| `gzip_types` | common web types | images are already compressed — skip them |
| `gzip_min_length` | 1024 bytes | tiny responses aren't worth compressing |
| `gzip_comp_level` | 6 (1-9) | sweet spot — fast compression, good ratio |

Test: `curl -H "Accept-Encoding: gzip" -I http://localhost:8082` → look for `Content-Encoding: gzip`

---

## Firewall (ufw)

Port 8081 is opened by Docker directly. To block external access while keeping nginx proxy working:

```bash
sudo ufw allow 22      # SSH — always first
sudo ufw allow 80      # nginx code_execution
sudo ufw allow 8082    # nginx mysite
sudo ufw deny 8081     # block direct agent access from internet
echo "y" | sudo ufw enable
sudo ufw status
```

Result: port 8081 is unreachable from the internet but nginx can still proxy to it on localhost.

---

## Key Concepts

### Port-based routing
nginx routes requests to different server blocks by the port they arrive on.
- Request on `:80` → matches `listen 80` → code_execution block
- Request on `:8082` → matches `listen 8082` → mysite block

### server_name routing (domain-based)
When you have a real domain, nginx routes by the `Host` header:
```nginx
server { server_name app.example.com; ... }   # matches this domain
server { server_name blog.example.com; ... }  # matches this domain
```
Without a domain, use `server_name _;` (catch-all) and differentiate by port instead.

### proxy_pass path rewriting
```nginx
location /health {
    proxy_pass http://127.0.0.1:8081/v1/healthz;
}
# browser: /health → backend sees: /v1/healthz
```
The `location` prefix is replaced by the path in `proxy_pass`.

### Trailing slash rule
```nginx
proxy_pass http://localhost:8081;    # no path = keep URL as-is
proxy_pass http://localhost:8081/;   # trailing slash = strip location prefix
proxy_pass http://localhost:8081/v1; # specific path = replace location prefix
```

---

## Daily Commands

```bash
sudo nginx -t                           # validate config (always before reload)
sudo systemctl reload nginx             # apply changes, zero downtime
sudo systemctl restart nginx            # full restart (use only if reload fails)
sudo systemctl status nginx             # is it running?

sudo tail -f /var/log/nginx/access.log  # watch live requests
sudo tail -f /var/log/nginx/error.log   # watch live errors

sudo ufw status                         # check firewall rules
docker ps                               # check Java agent is running
curl http://localhost/v1/healthz        # verify agent through nginx
curl http://localhost:8082              # verify static site
```

---

## Common Errors

| Error | Cause | Fix |
|-------|-------|-----|
| `502 Bad Gateway` | nginx up, backend `:8081` not reachable | check `docker ps` |
| `403 Forbidden` | nginx can't read the file | `chmod 755` dir, `chmod 644` file |
| `404` from nginx | no matching `location` or wrong `root` | check path and `try_files` |
| `conflicting server_name` warning | two server blocks with same `server_name` on same port | use different ports or different `server_name` |
| `address already in use` | another process on that port | `sudo ss -tlnp \| grep <port>` |

---

## Backend (Render) Environment Variables

Now that nginx is in front of the agent, update the backend to go through nginx:

| Variable | Old value | New value |
|----------|-----------|-----------|
| `EXECUTOR_AGENT_BASE_URL` | `http://3.110.161.110:8081` | `http://3.110.161.110` |

Port 8081 is now firewall-blocked. All traffic must go through nginx on port 80.
